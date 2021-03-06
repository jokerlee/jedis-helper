/**
 *
 */
package com.github.phantomthief.jedis;

import static com.github.phantomthief.util.MoreSuppliers.lazy;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.propagate;
import static com.google.common.collect.Iterables.partition;
import static com.google.common.collect.Maps.newHashMapWithExpectedSize;
import static java.lang.reflect.Proxy.newProxyInstance;
import static java.util.Collections.singleton;
import static java.util.function.Function.identity;

import java.io.Closeable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.phantomthief.util.CursorIteratorEx;
import com.github.phantomthief.util.ThrowableBiConsumer;
import com.google.common.net.HostAndPort;

import redis.clients.jedis.BasicCommands;
import redis.clients.jedis.BinaryJedis;
import redis.clients.jedis.BinaryJedisCommands;
import redis.clients.jedis.BinaryShardedJedis;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCommands;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.PipelineBase;
import redis.clients.jedis.Response;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.ShardedJedis;
import redis.clients.jedis.ShardedJedisPipeline;
import redis.clients.jedis.ShardedJedisPool;
import redis.clients.jedis.Tuple;
import redis.clients.util.Pool;

/**
 * @author w.vela
 */
public class JedisHelper<P extends PipelineBase, J extends Closeable> {

    private static final Logger logger = LoggerFactory.getLogger(JedisHelper.class);

    public static final long SETNX_KEY_NOT_SET = 0L;
    public static final long SETNX_KEY_SET = 1L;
    public static final int LIMIT_INFINITY = -1;
    public static final String POSITIVE_INF = "+inf";
    public static final String NEGATIVE_INF = "-inf";
    public static final String NOT_EXIST = "NX";
    public static final String ALREADY_EXIST = "XX";
    public static final String SECONDS = "EX";
    public static final String MILLISECONDS = "PX";

    private static final String PIPELINE = "pipeline";
    private final static int PARTITION_SIZE = 100;
    private final Supplier<Object> poolFactory;
    private final BiConsumer<Object, Throwable> exceptionHandler;
    private final int pipelinePartitionSize;

    private final Supplier<Object> stopWatchStart;
    private final Consumer<StopTheWatch<Object>> stopWatchStop;

    private final Class<?> jedisType;
    private final Class<?> binaryJedisType;

    private final Supplier<BasicCommands> basicCommandsSupplier = lazy(this::getBasic0);
    private final Supplier<JedisCommands> jedisCommandsSupplier = lazy(this::get0);
    private final Supplier<BinaryJedisCommands> binaryJedisCommandsSupplier = lazy(
            this::getBinary0);

    private JedisHelper(Supplier<Object> poolFactory, //
            BiConsumer<Object, Throwable> handler, //
            int pipelinePartitionSize, //
            Class<?> jedisType, //
            Class<?> binaryJedisType, //
            Supplier<Object> stopWatchStart, //
            Consumer<StopTheWatch<Object>> stopWatchStop) {
        this.poolFactory = poolFactory;
        this.exceptionHandler = handler;
        this.pipelinePartitionSize = pipelinePartitionSize;
        this.jedisType = jedisType;
        this.binaryJedisType = binaryJedisType;
        this.stopWatchStart = stopWatchStart;
        this.stopWatchStop = stopWatchStop;
    }

    public static String getShardBitKey(long bit, String keyPrefix, int keyHashRange) {
        return keyPrefix + "_" + (bit / keyHashRange);
    }

    public static Map<Long, String> getShardBitKeys(Collection<Long> bits, String keyPrefix,
            int keyHashRange) {
        Map<Long, String> result = new HashMap<>();
        for (Long bit : bits) {
            result.put(bit, getShardBitKey(bit, keyPrefix, keyHashRange));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Builder<ShardedJedisPipeline, ShardedJedis, ShardedJedisPool>
            newShardedBuilder(Supplier<ShardedJedisPool> poolFactory) {
        Builder<ShardedJedisPipeline, ShardedJedis, ShardedJedisPool> builder = new Builder<>();
        builder.poolFactory = (Supplier) poolFactory;
        builder.jedisType = ShardedJedis.class;
        builder.binaryJedisType = BinaryShardedJedis.class;
        return builder;
    }

    @SuppressWarnings("unchecked")
    public static Builder<Pipeline, Jedis, JedisPool> newBuilder(Supplier<JedisPool> poolFactory) {
        Builder<Pipeline, Jedis, JedisPool> builder = new Builder<>();
        builder.poolFactory = (Supplier) poolFactory;
        builder.jedisType = Jedis.class;
        builder.binaryJedisType = BinaryJedis.class;
        return builder;
    }

    public <K, V> Map<K, V> pipeline(Iterable<K> keys, BiFunction<P, K, Response<V>> function) {
        return pipeline(keys, function, identity());
    }

    public <K, V, T> Map<K, T> pipeline(Iterable<K> keys, BiFunction<P, K, Response<V>> function,
            Function<V, T> decoder) {
        int size;
        if (keys != null && keys instanceof Collection) {
            size = ((Collection<K>) keys).size();
        } else {
            size = 16;
        }
        Map<K, T> result = newHashMapWithExpectedSize(size);
        if (keys != null) {
            Iterable<List<K>> partition = partition(keys, pipelinePartitionSize);
            for (List<K> list : partition) {
                Object pool = poolFactory.get();
                HostAndPort jedisInfo = null;
                Object stopWatch = stopWatchStart();
                try (J jedis = getJedis(pool)) {
                    jedisInfo = getJedisInfo(jedis);
                    P pipeline = pipeline(jedis);
                    Map<K, Response<V>> thisMap = new HashMap<>(list.size());
                    for (K key : list) {
                        Response<V> apply = function.apply(pipeline, key);
                        if (apply != null) {
                            thisMap.put(key, apply);
                        }
                    }
                    syncPipeline(pipeline);
                    stopWatchStop(stopWatch, jedisInfo, PIPELINE, null);
                    thisMap.forEach((key, value) -> result.put(key, decoder.apply(value.get())));
                } catch (Throwable e) {
                    exceptionHandler.accept(pool, e);
                    stopWatchStop(stopWatch, jedisInfo, PIPELINE, e);
                }
            }
        }
        return result;
    }

    public BasicCommands getBasic() {
        return basicCommandsSupplier.get();
    }

    private BasicCommands getBasic0() {
        return (BasicCommands) newProxyInstance(jedisType.getClassLoader(),
                jedisType.getInterfaces(), new PoolableJedisCommands());
    }

    public JedisCommands get() {
        return jedisCommandsSupplier.get();
    }

    private JedisCommands get0() {
        return (JedisCommands) newProxyInstance(jedisType.getClassLoader(),
                jedisType.getInterfaces(), new PoolableJedisCommands());
    }

    public BinaryJedisCommands getBinary() {
        return binaryJedisCommandsSupplier.get();
    }

    private BinaryJedisCommands getBinary0() {
        return (BinaryJedisCommands) newProxyInstance(binaryJedisType.getClassLoader(),
                binaryJedisType.getInterfaces(), new PoolableJedisCommands());
    }

    private void syncPipeline(P pipeline) {
        if (pipeline instanceof Pipeline) {
            ((Pipeline) pipeline).sync();
        } else if (pipeline instanceof ShardedJedisPipeline) {
            ((ShardedJedisPipeline) pipeline).sync();
        }
    }

    private HostAndPort getJedisInfo(Object obj) {
        if (obj instanceof Jedis) {
            Jedis jedis = (Jedis) obj;
            return HostAndPort.fromParts(jedis.getClient().getHost(), jedis.getClient().getPort());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private J getJedis(Object pool) {
        if (pool instanceof Pool) {
            return ((Pool<J>) pool).getResource();
        } else {
            throw new IllegalArgumentException("invalid pool:" + pool);
        }
    }

    @SuppressWarnings("unchecked")
    private P pipeline(J jedis) {
        if (jedis instanceof Jedis) {
            return (P) ((Jedis) jedis).pipelined();
        } else if (jedis instanceof ShardedJedis) {
            return (P) ((ShardedJedis) jedis).pipelined();
        } else {
            throw new IllegalArgumentException("invalid jedis:" + jedis);
        }
    }

    public boolean getShardBit(long bit, String keyPrefix, int keyHashRange) {
        return getShardBit(singleton(bit), keyPrefix, keyHashRange).getOrDefault(bit, false);
    }

    public Map<Long, Boolean> getShardBit(Collection<Long> bits, String keyPrefix,
            int keyHashRange) {
        return pipeline(bits, (p, bit) -> p.getbit(getShardBitKey(bit, keyPrefix, keyHashRange),
                bit % keyHashRange));
    }

    public long getShardBitCount(String keyPrefix, int keyHashRange, long start, long end) {
        return generateKeys(keyPrefix, keyHashRange, start, end).values().stream()
                .mapToLong(get()::bitcount).sum();
    }

    public boolean setShardBit(long bit, String keyPrefix, int keyHashRange) {
        return setShardBit(singleton(bit), keyPrefix, keyHashRange).get(bit);
    }

    public boolean setShardBit(long bit, String keyPrefix, int keyHashRange, boolean value) {
        return setShardBitSet(singleton(bit), keyPrefix, keyHashRange, value).get(bit);
    }

    public Map<Long, Boolean> setShardBitSet(Collection<Long> bits, String keyPrefix,
            int keyHashRange, boolean value) {
        return pipeline(bits, (p, bit) -> p.setbit(getShardBitKey(bit, keyPrefix, keyHashRange),
                bit % keyHashRange, value));
    }

    public Map<Long, Boolean> setShardBit(Collection<Long> bits, String keyPrefix,
            int keyHashRange) {
        return setShardBitSet(bits, keyPrefix, keyHashRange, true);
    }

    public void delShardBit(String keyPrefix, int keyHashRange, long start, long end) {
        Map<Long, String> allKeys = generateKeys(keyPrefix, keyHashRange, start, end);
        allKeys.values().forEach(get()::del);
    }

    public Stream<Long> iterateShardBit(String keyPrefix, int keyHashRange, long start, long end) {
        Map<Long, String> allKeys = generateKeys(keyPrefix, keyHashRange, start, end);
        return allKeys.entrySet().stream().flatMap(this::mapToLong);
    }

    private Map<Long, String> generateKeys(String keyPrefix, int keyHashRange, long start,
            long end) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (long i = start; i <= end; i += keyHashRange) {
            result.put((i / keyHashRange) * keyHashRange, keyPrefix + "_" + (i / keyHashRange));
        }
        return result;
    }

    private Stream<Long> mapToLong(Entry<Long, String> entry) {
        byte[] bytes = getBinary().get(entry.getValue().getBytes());
        List<Long> result = new ArrayList<>();
        if (bytes != null && bytes.length > 0) {
            for (int i = 0; i < (bytes.length * 8); i++) {
                if ((bytes[i / 8] & (1 << (7 - (i % 8)))) != 0) {
                    result.add(entry.getKey() + i);
                }
            }
        }
        return result.stream();
    }

    @SuppressWarnings("RedundantTypeArguments")
    public Stream<String> scan(ScanParams params) {
        // javac cannot infer types...
        return this.<String, String> scan((j, c) -> {
            if (j instanceof Jedis) {
                return ((Jedis) j).scan(c, params);
            } else if (j instanceof ShardedJedis) {
                throw new UnsupportedOperationException();
            } else {
                throw new UnsupportedOperationException();
            }
        }, ScanResult::getStringCursor, "0").stream();
    }

    public Stream<Entry<String, String>> hscan(String key) {
        return hscan(key, new ScanParams());
    }

    @SuppressWarnings("RedundantTypeArguments")
    public Stream<Entry<String, String>> hscan(String key, ScanParams params) {
        // javac cannot infer types...
        return this.<String, Entry<String, String>> scan((j, c) -> {
            if (j instanceof Jedis) {
                return ((Jedis) j).hscan(key, c, params);
            } else if (j instanceof ShardedJedis) {
                return ((ShardedJedis) j).hscan(key, c, params);
            } else {
                throw new UnsupportedOperationException();
            }
        }, ScanResult::getStringCursor, "0").stream();
    }


    public Stream<Tuple> zscan(String key) {
        return zscan(key, new ScanParams());
    }

    @SuppressWarnings("RedundantTypeArguments")
    public Stream<Tuple> zscan(String key, ScanParams params) {
        // javac cannot infer types...
        return this.<String, Tuple> scan((j, c) -> {
            if (j instanceof Jedis) {
                return ((Jedis) j).zscan(key, c, params);
            } else if (j instanceof ShardedJedis) {
                return ((ShardedJedis) j).zscan(key, c, params);
            } else {
                throw new UnsupportedOperationException();
            }
        }, ScanResult::getStringCursor, "0").stream();
    }

    public Stream<String> sscan(String key) {
        return sscan(key, new ScanParams());
    }

    @SuppressWarnings("RedundantTypeArguments")
    public Stream<String> sscan(String key, ScanParams params) {
        // javac cannot infer types...
        return this.<String, String> scan((j, c) -> {
            if (j instanceof Jedis) {
                return ((Jedis) j).sscan(key, c, params);
            } else if (j instanceof ShardedJedis) {
                return ((ShardedJedis) j).sscan(key, c, params);
            } else {
                throw new UnsupportedOperationException();
            }
        }, ScanResult::getStringCursor, "0").stream();
    }

    private <K, R> CursorIteratorEx<R, K, ScanResult<R>> scan(
            BiFunction<J, K, ScanResult<R>> scanFunction,
            Function<ScanResult<R>, K> cursorExtractor, K initCursor) {
        return CursorIteratorEx.newBuilder() //
                .withDataRetriever((K cursor) -> {
                    Object pool = poolFactory.get();
                    try (J jedis = getJedis(pool)) {
                        return scanFunction.apply(jedis, cursor);
                    } catch (Throwable e) {
                        exceptionHandler.accept(pool, e);
                        throw propagate(e);
                    }
                }) //
                .withCursorExtractor(cursorExtractor) //
                .withDataExtractor((ScanResult<R> s) -> s.getResult().iterator()) //
                .withEndChecker(s -> "0".equals(s) || s == null) //
                .withInitCursor(initCursor) //
                .build();
    }

    private Object stopWatchStart() {
        if (stopWatchStart != null) {
            return stopWatchStart.get();
        } else {
            return null;
        }
    }

    private void stopWatchStop(Object obj, HostAndPort hostAndPort, String op, Throwable e) {
        if (stopWatchStop != null) {
            stopWatchStop.accept(new StopTheWatch<>(obj, hostAndPort, op, e));
        }
    }

    public static final class Builder<P extends PipelineBase, J extends Closeable, O> {

        private Supplier<Object> poolFactory;
        private BiConsumer<O, Throwable> exceptionHandler;
        private int pipelinePartitionSize;

        private Supplier<Object> stopWatchStart;
        private Consumer<StopTheWatch<Object>> stopWatchStop;

        private Class<?> jedisType;
        private Class<?> binaryJedisType;

        public Builder<P, J, O>
                withExceptionHandler(ThrowableBiConsumer<O, Throwable, Exception> handler) {
            checkNotNull(handler);
            this.exceptionHandler = (t, e) -> {
                try {
                    handler.accept(t, e);
                } catch (Throwable ex) {
                    logger.error("", ex);
                }
            };
            return this;
        }

        public Builder<P, J, O> withPipelinePartitionSize(int size) {
            this.pipelinePartitionSize = size;
            return this;
        }

        @SuppressWarnings("unchecked")
        public <T> Builder<P, J, O> enableProfiler(Supplier<T> stopWatchSupplier,
                Consumer<StopTheWatch<T>> stopTheWatch) {
            this.stopWatchStart = (Supplier<Object>) stopWatchSupplier;
            this.stopWatchStop = (Consumer) stopTheWatch;
            return this;
        }

        @SuppressWarnings("unchecked")
        public JedisHelper<P, J> build() {
            ensure();
            return new JedisHelper<>(poolFactory, (BiConsumer<Object, Throwable>) exceptionHandler,
                    pipelinePartitionSize, jedisType, binaryJedisType, stopWatchStart,
                    stopWatchStop);
        }

        private void ensure() {
            if (pipelinePartitionSize <= 0) {
                pipelinePartitionSize = PARTITION_SIZE;
            }
            if (exceptionHandler == null) {
                exceptionHandler = (t, e) -> {};
            }
        }
    }

    public static final class StopTheWatch<T> {

        private final T object;
        private final String method;
        private final HostAndPort hostAndPort;
        private final Throwable exception;

        private StopTheWatch(T object, HostAndPort hostAndPort, String method,
                Throwable exception) {
            this.object = object;
            this.method = method;
            this.hostAndPort = hostAndPort;
            this.exception = exception;
        }

        public T getObject() {
            return object;
        }

        public String getMethod() {
            return method;
        }

        public HostAndPort getHostAndPort() {
            return hostAndPort;
        }

        public Throwable getException() {
            return exception;
        }
    }

    private final class PoolableJedisCommands implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object stopWatch = stopWatchStart();
            HostAndPort jedisInfo = null;
            Object pool = poolFactory.get();
            try (J jedis = getJedis(pool)) {
                jedisInfo = getJedisInfo(jedis);
                Object result = method.invoke(jedis, args);
                stopWatchStop(stopWatch, jedisInfo, method.getName(), null);
                return result;
            } catch (Throwable e) {
                Throwable exception;
                if (e instanceof InvocationTargetException) {
                    exception = ((InvocationTargetException) e).getTargetException();
                } else {
                    exception = e;
                }
                exceptionHandler.accept(pool, exception);
                stopWatchStop(stopWatch, jedisInfo, method.getName(), exception);
                throw e;
            }
        }
    }
}
