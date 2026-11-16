// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.dash.offline.DashDownloader;
import androidx.media3.exoplayer.hls.offline.HlsDownloader;
import androidx.media3.exoplayer.offline.Downloader;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the process-wide {@link SimpleCache} shared by background pre-caching and {@code ExoPlayer}
 * playback.
 *
 * <p>Pre-caching is pure network-to-disk I/O; this class never creates an {@code ExoPlayer}, a
 * surface or a {@code MediaCodec} decoder.
 */
@OptIn(markerClass = UnstableApi.class)
final class VideoCacheManager {
  /** Name of the cache directory inside the application cache directory. */
  static final String CACHE_DIRECTORY_NAME = "flutter_video_cache";

  /** Maximum size of the on-disk cache before least-recently-used spans are evicted. */
  static final long MAX_CACHE_SIZE_BYTES = 100L * 1024L * 1024L;

  /** Number of bytes pre-cached when the caller does not specify a budget. */
  static final long DEFAULT_PRECACHE_BYTES = 2L * 1024L * 1024L;

  private static final int PRECACHE_THREADS = 2;

  private static volatile VideoCacheManager instance;

  /** Receives the outcome of a pre-cache operation. */
  interface Callback {
    /** Called when the operation finished, including when it was cancelled. */
    void onSuccess();

    /** Called when the operation failed. */
    void onError(@NonNull Throwable error);
  }

  /** Cancellation handle for an in-flight pre-cache operation. */
  private static final class CacheTask {
    final AtomicBoolean canceled = new AtomicBoolean(false);
    @Nullable volatile CacheWriter cacheWriter;
    @Nullable volatile Downloader downloader;

    void cancel() {
      canceled.set(true);
      CacheWriter writer = cacheWriter;
      if (writer != null) {
        writer.cancel();
      }
      Downloader dl = downloader;
      if (dl != null) {
        dl.cancel();
      }
    }
  }

  @NonNull private final SimpleCache simpleCache;
  @Nullable private final DataSource.Factory upstreamFactoryOverride;
  @NonNull private final ExecutorService executorService;
  @NonNull private final ConcurrentHashMap<String, CacheTask> activeTasks = new ConcurrentHashMap<>();

  /**
   * Returns the process-wide instance, creating it on first use.
   *
   * @param context any context; the application context is retained.
   * @return the shared manager.
   */
  @NonNull
  static VideoCacheManager getInstance(@NonNull Context context) {
    VideoCacheManager result = instance;
    if (result == null) {
      synchronized (VideoCacheManager.class) {
        result = instance;
        if (result == null) {
          Context appContext = context.getApplicationContext();
          SimpleCache cache =
              new SimpleCache(
                  new File(appContext.getCacheDir(), CACHE_DIRECTORY_NAME),
                  new LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE_BYTES),
                  new StandaloneDatabaseProvider(appContext));
          result =
              new VideoCacheManager(
                  cache, null, Executors.newFixedThreadPool(PRECACHE_THREADS));
          instance = result;
        }
      }
    }
    return result;
  }

  @VisibleForTesting
  VideoCacheManager(
      @NonNull SimpleCache simpleCache,
      @Nullable DataSource.Factory upstreamFactoryOverride,
      @NonNull ExecutorService executorService) {
    this.simpleCache = simpleCache;
    this.upstreamFactoryOverride = upstreamFactoryOverride;
    this.executorService = executorService;
  }

  /** Returns the shared cache. */
  @NonNull
  Cache getCache() {
    return simpleCache;
  }

  /** Returns the key under which a resource is stored. */
  @NonNull
  static String resolveCacheKey(@NonNull String uri, @Nullable String cacheKey) {
    return (cacheKey != null && !cacheKey.isEmpty()) ? cacheKey : uri;
  }

  /**
   * Wraps {@code upstreamFactory} so reads are served from, and written to, the shared cache.
   *
   * @param upstreamFactory factory used on cache misses.
   * @return the caching factory.
   */
  @NonNull
  CacheDataSource.Factory createCacheDataSourceFactory(@NonNull DataSource.Factory upstreamFactory) {
    return new CacheDataSource.Factory()
        .setCache(simpleCache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
  }

  /**
   * Caches up to {@code maxCacheBytes} of a remote video on a background thread.
   *
   * @param uri remote video URI.
   * @param httpHeaders request headers.
   * @param userAgent optional user agent.
   * @param streamingFormat format of the stream.
   * @param cacheKey optional custom cache key.
   * @param maxCacheBytes optional byte budget; defaults to {@link #DEFAULT_PRECACHE_BYTES}.
   * @param callback receives the outcome.
   */
  void precache(
      @NonNull String uri,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent,
      @NonNull VideoAsset.StreamingFormat streamingFormat,
      @Nullable String cacheKey,
      @Nullable Long maxCacheBytes,
      @NonNull Callback callback) {
    if (maxCacheBytes != null && maxCacheBytes <= 0) {
      callback.onError(new IllegalArgumentException("maxCacheBytes must be greater than zero."));
      return;
    }
    final long targetBytes = maxCacheBytes != null ? maxCacheBytes : DEFAULT_PRECACHE_BYTES;
    final String key = resolveCacheKey(uri, cacheKey);
    final VideoAsset.StreamingFormat format = detectFormat(uri, streamingFormat);

    final CacheTask task = new CacheTask();
    CacheTask previous = activeTasks.put(key, task);
    if (previous != null) {
      previous.cancel();
    }

    try {
      executorService.execute(
          () -> {
            try {
              runPrecache(task, uri, httpHeaders, userAgent, format, key, targetBytes);
              callback.onSuccess();
            } catch (Throwable e) {
              if (task.canceled.get() || e instanceof InterruptedIOException) {
                // Cancellation is not an error.
                callback.onSuccess();
              } else {
                callback.onError(e);
              }
            } finally {
              activeTasks.remove(key, task);
            }
          });
    } catch (RuntimeException e) {
      activeTasks.remove(key, task);
      callback.onError(e);
    }
  }

  // TODO: Migrate to the non-deprecated downloader factories once available in all supported versions.
  @SuppressWarnings("deprecation")
  private void runPrecache(
      @NonNull CacheTask task,
      @NonNull String uri,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent,
      @NonNull VideoAsset.StreamingFormat format,
      @NonNull String key,
      long targetBytes)
      throws IOException, InterruptedException {
    if (task.canceled.get()) {
      return;
    }
    DataSource.Factory upstream = createUpstreamFactory(httpHeaders, userAgent);
    CacheDataSource.Factory cacheFactory = createCacheDataSourceFactory(upstream);

    if (format == VideoAsset.StreamingFormat.HTTP_LIVE
        || format == VideoAsset.StreamingFormat.DYNAMIC_ADAPTIVE) {
      MediaItem.Builder builder = new MediaItem.Builder().setUri(Uri.parse(uri));
      builder.setCustomCacheKey(key);
      Downloader downloader;
      if (format == VideoAsset.StreamingFormat.HTTP_LIVE) {
        builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        downloader = new HlsDownloader(builder.build(), cacheFactory);
      } else {
        builder.setMimeType(MimeTypes.APPLICATION_MPD);
        downloader = new DashDownloader(builder.build(), cacheFactory);
      }
      task.downloader = downloader;
      if (task.canceled.get()) {
        downloader.cancel();
        return;
      }
      final AtomicBoolean reachedBudget = new AtomicBoolean(false);
      try {
        downloader.download(
            (contentLength, bytesDownloaded, percentDownloaded) -> {
              if (bytesDownloaded >= targetBytes && reachedBudget.compareAndSet(false, true)) {
                // Intentional partial stop once the byte budget is met.
                task.downloader.cancel();
              }
            });
      } catch (IOException | InterruptedException | RuntimeException e) {
        if (!reachedBudget.get()) {
          throw e;
        }
      }
    } else {
      DataSpec dataSpec =
          new DataSpec.Builder()
              .setUri(Uri.parse(uri))
              .setKey(key)
              .setPosition(0)
              .setLength(targetBytes)
              .build();
      CacheWriter writer = new CacheWriter(cacheFactory.createDataSource(), dataSpec, null, null);
      task.cacheWriter = writer;
      if (task.canceled.get()) {
        writer.cancel();
      }
      writer.cache();
    }
  }

  @NonNull
  private DataSource.Factory createUpstreamFactory(
      @NonNull Map<String, String> httpHeaders, @Nullable String userAgent) {
    if (upstreamFactoryOverride != null) {
      return upstreamFactoryOverride;
    }
    DefaultHttpDataSource.Factory httpFactory =
        new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true);
    if (userAgent != null) {
      httpFactory.setUserAgent(userAgent);
    }
    if (!httpHeaders.isEmpty()) {
      httpFactory.setDefaultRequestProperties(httpHeaders);
    }
    return httpFactory;
  }

  @NonNull
  private static VideoAsset.StreamingFormat detectFormat(
      @NonNull String uri, @NonNull VideoAsset.StreamingFormat hint) {
    if (hint != VideoAsset.StreamingFormat.UNKNOWN) {
      return hint;
    }
    String path = Uri.parse(uri).getPath();
    if (path != null) {
      String lower = path.toLowerCase(java.util.Locale.ROOT);
      if (lower.endsWith(".m3u8")) {
        return VideoAsset.StreamingFormat.HTTP_LIVE;
      }
      if (lower.endsWith(".mpd")) {
        return VideoAsset.StreamingFormat.DYNAMIC_ADAPTIVE;
      }
    }
    return VideoAsset.StreamingFormat.UNKNOWN;
  }

  /**
   * Cancels an in-flight pre-cache for the given resource. A no-op when none is active.
   *
   * @param uri video URI.
   * @param cacheKey optional custom cache key.
   */
  void cancelPrecache(@NonNull String uri, @Nullable String cacheKey) {
    CacheTask task = activeTasks.get(resolveCacheKey(uri, cacheKey));
    if (task != null) {
      task.cancel();
    }
  }

  /**
   * Cancels every active pre-cache and removes all cached data on a background thread.
   *
   * @param callback invoked once the cache has been cleared.
   */
  void clearCache(@NonNull Callback callback) {
    for (CacheTask task : new ArrayList<>(activeTasks.values())) {
      task.cancel();
    }
    try {
      executorService.execute(
          () -> {
            try {
              for (String key : new HashSet<>(simpleCache.getKeys())) {
                simpleCache.removeResource(key);
              }
              callback.onSuccess();
            } catch (Throwable e) {
              callback.onError(e);
            }
          });
    } catch (RuntimeException e) {
      callback.onError(e);
    }
  }

  /** Returns whether a pre-cache is active for the given key. Visible for testing. */
  @VisibleForTesting
  boolean hasActiveTask(@NonNull String uri, @Nullable String cacheKey) {
    return activeTasks.containsKey(resolveCacheKey(uri, cacheKey));
  }
}
