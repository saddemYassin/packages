// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@UnstableApi
public final class VideoCacheManagerTest {
  private static final String URI = "https://example.com/video.mp4";
  private static final int FILE_SIZE = 4096;

  private SimpleCache cache;
  private ExecutorService executor;
  private VideoCacheManager manager;
  private FakeDataSet dataSet;
  private File cacheDir;

  @Before
  public void setUp() throws Exception {
    cacheDir = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "test_cache");
    cache =
        new SimpleCache(
            cacheDir,
            new NoOpCacheEvictor(),
            new StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()));
    executor = Executors.newFixedThreadPool(2);
    dataSet = new FakeDataSet().setRandomData(URI, FILE_SIZE);
    dataSet.setRandomData("https://example.com/other.mp4?token=1", FILE_SIZE);
    dataSet.setRandomData("https://example.com/other.mp4?token=2", FILE_SIZE);
    DataSource.Factory upstream = () -> new FakeDataSource(dataSet);
    manager = new VideoCacheManager(cache, upstream, executor);
  }

  @After
  public void tearDown() {
    executor.shutdownNow();
    cache.release();
  }

  private static final class Result implements VideoCacheManager.Callback {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onSuccess() {
      latch.countDown();
    }

    @Override
    public void onError(Throwable e) {
      error.set(e);
      latch.countDown();
    }

    void await() throws InterruptedException {
      assertTrue("callback not invoked", latch.await(10, TimeUnit.SECONDS));
    }
  }

  private Result precache(String uri, String key, Long maxBytes) throws InterruptedException {
    Result result = new Result();
    manager.precache(
        uri,
        new HashMap<>(),
        null,
        VideoAsset.StreamingFormat.UNKNOWN,
        key,
        maxBytes,
        result);
    result.await();
    return result;
  }

  @Test
  public void resolveCacheKeyPrefersNonEmptyCacheKey() {
    assertEquals("k", VideoCacheManager.resolveCacheKey(URI, "k"));
    assertEquals(URI, VideoCacheManager.resolveCacheKey(URI, null));
    assertEquals(URI, VideoCacheManager.resolveCacheKey(URI, ""));
  }

  @Test
  public void precacheCachesRequestedPrefixOnly() throws Exception {
    Result result = precache(URI, null, 1000L);

    assertNull(result.error.get());
    assertTrue(cache.isCached(URI, 0, 1000));
    assertFalse(cache.isCached(URI, 0, FILE_SIZE));
  }

  @Test
  public void precacheDefaultsToTwoMegabytesWhichCoversSmallFile() throws Exception {
    Result result = precache(URI, null, null);

    assertNull(result.error.get());
    assertTrue(cache.isCached(URI, 0, FILE_SIZE));
  }

  @Test
  public void precacheUsesCustomCacheKey() throws Exception {
    String uri = "https://example.com/other.mp4?token=1";
    precache(uri, "stable", 512L);

    assertTrue(cache.isCached("stable", 0, 512));
    assertFalse(cache.isCached(uri, 0, 512));
  }

  @Test
  public void differentCacheKeysAreIsolated() throws Exception {
    precache("https://example.com/other.mp4?token=1", "a", 512L);

    assertTrue(cache.isCached("a", 0, 512));
    assertFalse(cache.isCached("b", 0, 512));
  }

  @Test
  public void precacheOfAlreadyCachedRangeMakesNoUpstreamRequests() throws Exception {
    precache(URI, null, 1000L);

    final java.util.List<FakeDataSource> sources = new java.util.ArrayList<>();
    final int[] upstreamOpens = {0};
    manager =
        new VideoCacheManager(
            cache,
            () -> {
              FakeDataSource source = new FakeDataSource(dataSet);
              sources.add(source);
              return source;
            },
            executor);
    Result result = precache(URI, null, 1000L);

    assertNull(result.error.get());
    for (FakeDataSource source : sources) {
      upstreamOpens[0] += source.getAndClearOpenedDataSpecs().length;
    }
    assertEquals(0, upstreamOpens[0]);
  }

  @Test
  public void precacheRejectsNonPositiveByteBudget() throws Exception {
    Result result = precache(URI, null, 0L);

    assertTrue(result.error.get() instanceof IllegalArgumentException);
  }

  @Test
  public void precacheReportsUpstreamFailures() throws Exception {
    Result result = precache("https://example.com/missing.mp4", null, 100L);

    assertNotNull(result.error.get());
  }

  @Test
  public void cancelPrecacheWithNoActiveTaskIsNoOp() {
    manager.cancelPrecache(URI, null);
    assertFalse(manager.hasActiveTask(URI, null));
  }

  @Test
  public void cancelPrecacheCompletesCleanlyWithoutError() throws Exception {
    Result result = new Result();
    manager.precache(
        URI,
        new HashMap<>(),
        null,
        VideoAsset.StreamingFormat.UNKNOWN,
        null,
        1000L,
        result);
    manager.cancelPrecache(URI, null);
    result.await();

    assertNull(result.error.get());
  }

  @Test
  public void clearCacheRemovesAllCachedResources() throws Exception {
    precache(URI, null, 1000L);
    assertFalse(cache.getKeys().isEmpty());

    Result cleared = new Result();
    manager.clearCache(cleared);
    cleared.await();

    assertNull(cleared.error.get());
    assertTrue(cache.getKeys().isEmpty());
  }

  @Test
  public void cacheDataSourceFactoryIgnoresCacheOnError() {
    CacheDataSource.Factory factory =
        manager.createCacheDataSourceFactory(() -> new FakeDataSource(dataSet));
    assertNotNull(factory.createDataSource());
  }

  @Test
  public void getInstanceReturnsSharedSingleton() {
    VideoCacheManager a = VideoCacheManager.getInstance(ApplicationProvider.getApplicationContext());
    VideoCacheManager b = VideoCacheManager.getInstance(ApplicationProvider.getApplicationContext());
    assertSame(a, b);
    assertSame(a.getCache(), b.getCache());
  }
}
