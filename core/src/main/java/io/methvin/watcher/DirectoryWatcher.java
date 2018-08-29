/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.methvin.watcher;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.google.common.hash.HashCode;
import com.sun.nio.file.ExtendedWatchEventModifier;
import io.methvin.watcher.DirectoryChangeEvent.EventType;
import io.methvin.watchservice.MacOSXListeningWatchService;
import io.methvin.watchservice.WatchablePath;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DirectoryWatcher {

  /**
   * A builder for a {@link DirectoryWatcher}. Use {@code DirectoryWatcher.builder()} to get a new instance.
   */
  public static final class Builder {
    private List<Path> paths = Collections.emptyList();
    private List<Path> files = Collections.emptyList();
    private DirectoryChangeListener listener = (event -> {});
    private Logger logger = null;
    private boolean enableFileHashing = true;
    private WatchService watchService = null;

    private Builder() {}

    /**
     * Set multiple paths to watch.
     */
    public Builder paths(List<Path> paths) {
      this.paths = paths;
      return this;
    }

    /**
     * Set multiple files to watch.
     *
     * @param files Paths to files (they cannot be directories).
     */
    public Builder files(List<Path> files) {
      this.files = files;
      return this;
    }

    /**
     * Set a single path to watch.
     */
    public Builder path(Path path) {
      return paths(Collections.singletonList(path));
    }

    /**
     * Set a listener that will be called when a directory change event occurs.
     */
    public Builder listener(DirectoryChangeListener listener) {
      this.listener = listener;
      return this;
    }

    /**
     * Set a {@link WatchService} implementation that will be used by the watcher.
     *
     * By default, this detects your OS and either uses the native JVM watcher or the macOS watcher.
     */
    public Builder watchService(WatchService watchService) {
      this.watchService = watchService;
      return this;
    }

    /**
     * Set a logger to be used by the watcher. This defaults to {@code LoggerFactory.getLogger(DirectoryWatcher.class)}
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Defines whether file hashing should be used to catch duplicate events. Defaults to {@code true}.
     */
    public Builder fileHashing(boolean enabled) {
      this.enableFileHashing = enabled;
      return this;
    }

    public DirectoryWatcher build() throws IOException {
      if (watchService == null) {
        osDefaultWatchService();
      }
      if (logger == null) {
        staticLogger();
      }
      return new DirectoryWatcher(paths, files, listener, watchService, enableFileHashing, logger);
    }

    private Builder osDefaultWatchService() throws IOException {
      boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
      return watchService(isMac ? new MacOSXListeningWatchService() : FileSystems.getDefault().newWatchService());
    }

    private Builder staticLogger() {
      return logger(LoggerFactory.getLogger(DirectoryWatcher.class));
    }
  }

  /**
   * Get a new builder for a {@link DirectoryWatcher}.
   */
  public static Builder builder() {
    return new Builder();
  }

  private final Logger logger;

  private final WatchService watchService;
  private final List<Path> paths;
  private final List<Path> files;
  private final Set<Path> nonRecursivePaths;
  private final boolean isMac;
  private final DirectoryChangeListener listener;
  private final Map<Path, HashCode> pathHashes;
  private final Map<WatchKey, Path> keyRoots;

  // this is set to true/false depending on whether recursive watching is supported natively
  private Boolean fileTreeSupported = null;
  private boolean enableFileHashing;

  public DirectoryWatcher(List<Path> paths, List<Path> files, DirectoryChangeListener listener, WatchService watchService, boolean enableFileHashing, Logger logger) throws IOException {
    this.paths = paths;
    this.files = files;
    this.listener = listener;
    this.watchService = watchService;
    this.isMac = watchService instanceof MacOSXListeningWatchService;
    this.pathHashes = PathUtils.createHashCodeMap(paths);
    this.keyRoots = PathUtils.createKeyRootsMap();
    this.enableFileHashing = enableFileHashing;
    this.logger = logger;

    nonRecursivePaths = new HashSet<>();
    for (Path file: files) {
      Path parentFile = file.getParent();
      if (Files.exists(parentFile)) {
        if (!nonRecursivePaths.contains(file)) {
          nonRecursivePaths.add(parentFile);
          register(parentFile, false);
        } else {
          logger.debug("Parent file " + parentFile.toString() + " is on the watch");
        }
      } else {
        logger.error("Parent file " + parentFile.toString() + " of " + file.toString() + " does not exist");
      }
    }

    /* Register all recursive paths after the non recursive paths to invalidate
     * those non-recursive paths that are children of the recursive paths. */
    for (Path path : paths) {
      registerAll(path);
    }
  }

  /**
   * Asynchronously watch the directories using {@code ForkJoinPool.commonPool()} as the executor
   */
  public CompletableFuture<Void> watchAsync() {
    return watchAsync(ForkJoinPool.commonPool());
  }

  /**
   * Asynchronously watch the directories.
   *
   * @param executor the executor to use to watch asynchronously
   */
  public CompletableFuture<Void> watchAsync(Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      watch();
      return null;
    }, executor);
  }

  /**
   * Watch the directories. Block until either the listener stops watching or the DirectoryWatcher is closed.
   */
  public void watch() {
    for (;;) {
      if (!listener.isWatching()) {
        return;
      }
      // wait for key to be signalled
      WatchKey key;
      try {
        key = watchService.take();
      } catch (InterruptedException x) {
        return;
      }
      for (WatchEvent<?> event : key.pollEvents()) {
        try {
          WatchEvent.Kind<?> kind = event.kind();
          // Context for directory entry event is the file name of entry
          WatchEvent<Path> ev = PathUtils.cast(event);
          int count = ev.count();
          Path eventPath = ev.context();
          if (!keyRoots.containsKey(key)) {
            throw new IllegalStateException(
                "WatchService returned key [" + key + "] but it was not found in keyRoots!");
          }
          Path childPath = eventPath == null ? null : keyRoots.get(key).resolve(eventPath);
          logger.debug("{} [{}]", kind, childPath);

          // If a change in a directory of a non-recursive directory happens, ignore it
          if (Files.isDirectory(childPath, NOFOLLOW_LINKS)) {
            Path childParentPath = childPath.getParent();
            if (nonRecursivePaths.contains(childParentPath)) {
              logger.debug("Registration of " + childPath.toString() + " skipped as its parent is a non-recursive directory");
              continue;
            }
          }

          // if directory is created, and watching recursively, then register it and its sub-directories
          if (kind == OVERFLOW) {
            listener.onEvent(new DirectoryChangeEvent(EventType.OVERFLOW, childPath, count));
          } else if (eventPath == null) {
            throw new IllegalStateException("WatchService returned a null path for " + kind.name());
          } else if (kind == ENTRY_CREATE) {
            if (Files.isDirectory(childPath, NOFOLLOW_LINKS)) {
              if (!Boolean.TRUE.equals(fileTreeSupported)) {
                registerAll(childPath);
              }

              // Our custom Mac service sends subdirectory changes but the Windows/Linux do not.
              // Walk the file tree to make sure we send create events for any files that were created.
              if (!isMac) {
                Files.walkFileTree(childPath, new SimpleFileVisitor<Path>() {
                  @Override
                  public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    notifyCreateEvent(dir, count);
                    return FileVisitResult.CONTINUE;
                  }

                  @Override
                  public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    notifyCreateEvent(file, count);
                    return FileVisitResult.CONTINUE;
                  }

                });
              }
            }
            notifyCreateEvent(childPath, count);
          } else if (kind == ENTRY_MODIFY) {
            if (enableFileHashing || Files.isDirectory(childPath)) {
              // Note that existingHash may be null due to the file being created before we start listening
              // It's important we don't discard the event in this case
              HashCode existingHash = pathHashes.get(childPath);

              // newHash can be null when using File#delete() on windows - it generates MODIFY and DELETE in succession
              // in this case the MODIFY event can be safely ignored
              HashCode newHash = PathUtils.hash(childPath);

              if (newHash != null && !newHash.equals(existingHash)) {
                pathHashes.put(childPath, newHash);
                listener.onEvent(new DirectoryChangeEvent(EventType.MODIFY, childPath, count));
              } else if (newHash == null) {
                logger.debug("Failed to hash modified file [{}]. It may have been deleted.", childPath);
              }
            } else {
              listener.onEvent(new DirectoryChangeEvent(EventType.MODIFY, childPath, count));
            }
          } else if (kind == ENTRY_DELETE) {
            pathHashes.remove(childPath);
            listener.onEvent(new DirectoryChangeEvent(EventType.DELETE, childPath, count));
          }
        } catch (Exception e) {
          logger.debug("DirectoryWatcher got an exception while watching!", e);
          listener.onException(e);
        }
      }
      boolean valid = key.reset();
      if (!valid) {
        logger.debug("WatchKey for [{}] no longer valid; removing.", key.watchable());
        // remove the key from the keyRoots
        keyRoots.remove(key);
        // if there are no more keys left to watch, we can break out
        if (keyRoots.isEmpty()) {
          logger.debug("No more directories left to watch; terminating watcher.");
          break;
        }
      }
    }
  }


  public DirectoryChangeListener getListener() {
    return listener;
  }

  public void close() throws IOException {
    watchService.close();
  }

  private void registerAll(final Path start) throws IOException {
    if (!Boolean.FALSE.equals(fileTreeSupported)) {
      // Try using FILE_TREE modifier since we aren't certain that it's unsupported
      try {
        register(start, true);
        // We didn't get an UnsupportedOperationException so assume FILE_TREE is supported
        fileTreeSupported = true;
      } catch (UnsupportedOperationException e) {
        // UnsupportedOperationException should only happen if FILE_TREE is unsupported
        logger.debug("Assuming ExtendedWatchEventModifier.FILE_TREE is not supported", e);
        fileTreeSupported = false;
        // If we failed to use the FILE_TREE modifier, try again without
        registerAll(start);
      }
    } else {
      // Since FILE_TREE is unsupported, register root directory and sub-directories
      Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          register(dir, false);
          return FileVisitResult.CONTINUE;
        }
      });
    }

    // Remove those non recursive paths which are children of the recursive paths
    nonRecursivePaths.iterator().forEachRemaining(child -> {
      if (child.startsWith(start)) {
        nonRecursivePaths.remove(child);
      }
    });
  }

  // Internal method to be used by registerAll
  private void register(Path directory, boolean useFileTreeModifier) throws IOException {
    logger.debug("Registering [{}].", directory);
    Watchable watchable = isMac ? new WatchablePath(directory) : directory;
    WatchEvent.Modifier[] modifiers = useFileTreeModifier
        ? new WatchEvent.Modifier[] {ExtendedWatchEventModifier.FILE_TREE}
        : new WatchEvent.Modifier[] {};
    WatchEvent.Kind<?>[] kinds = new WatchEvent.Kind<?>[] {ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY};
    WatchKey watchKey = watchable.register(watchService, kinds, modifiers);
    keyRoots.put(watchKey, directory);
  }

  private void notifyCreateEvent(Path path, int count) throws IOException {
    if (enableFileHashing || Files.isDirectory(path)) {
      HashCode newHash = PathUtils.hash(path);
      if (newHash == null) {
        logger.debug("Failed to hash created file [{}]. It may have been deleted.", path);
        return;
      }
      // Notify for the file create if not already notified
      if (!pathHashes.containsKey(path)) {
        logger.debug("{} [{}]", EventType.CREATE, path);
        listener.onEvent(new DirectoryChangeEvent(EventType.CREATE, path, count));
        pathHashes.put(path, newHash);
      }
    } else {
      logger.debug("{} [{}]", EventType.CREATE, path);
      listener.onEvent(new DirectoryChangeEvent(EventType.CREATE, path, count));
    }
  }

}
