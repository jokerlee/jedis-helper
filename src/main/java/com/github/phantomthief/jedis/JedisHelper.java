/**
 * 
 */
package com.github.phantomthief.jedis;

import java.io.Closeable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.BaseStream;
import java.util.stream.Stream;

import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;

import redis.clients.jedis.BinaryJedis;
import redis.clients.jedis.BinaryJedisCommands;
import redis.clients.jedis.BinaryShardedJedis;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCommands;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.PipelineBase;
import redis.clients.jedis.Response;
import redis.clients.jedis.ShardedJedis;
import redis.clients.jedis.ShardedJedisPipeline;
import redis.clients.jedis.ShardedJedisPool;
import redis.clients.util.Pool;

/**
 * @author w.vela
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class JedisHelper<P extends PipelineBase, J extends Closeable> {

    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(getClass());

    private final static int PARTITION_SIZE = 100;

    private final Supplier<Object> poolFactory;
    private final BiConsumer<Object, Throwable> exceptionHandler;

    private final Class<?> jedisType;
    private final Class<?> binaryJedisType;

    /**
     * @param poolFactory
     * @param exceptionHandler
     * @param jedisType
     * @param binaryJedisType
     */
    private JedisHelper(Supplier<Object> poolFactory, //
            BiConsumer<Object, Throwable> exceptionHandler, //
            Class<?> jedisType, //
            Class<?> binaryJedisType) {
        this.poolFactory = poolFactory;
        this.exceptionHandler = exceptionHandler;
        this.jedisType = jedisType;
        this.binaryJedisType = binaryJedisType;
    }

    public <K, V, E extends BaseStream<K, E>> Map<K, V> pipeline(E keys,
            BiFunction<P, K, Response<V>> function) {
        Map<K, V> result = new HashMap<>();
        if (keys != null) {
            Iterator<List<K>> partition = Iterators.partition(keys.iterator(), PARTITION_SIZE);
            for (Iterator<List<K>> iterator = partition; iterator.hasNext();) {
                List<K> list = iterator.next();

                Object pool = poolFactory.get();
                try (J jedis = getJedis(pool)) {
                    P pipeline = pipeline(jedis);
                    Map<K, Response<V>> thisMap = new HashMap<>(list.size());
                    for (K key : list) {
                        Response<V> apply = function.apply(pipeline, key);
                        thisMap.put(key, apply);
                    }
                    syncPipeline(pipeline);
                    for (Entry<K, Response<V>> entry : thisMap.entrySet()) {
                        if (entry.getValue() != null) {
                            result.put(entry.getKey(), entry.getValue().get());
                        }
                    }
                } catch (Throwable e) {
                    exceptionHandler.accept(pool, e);
                } finally {

                }
            }
        }
        return result;
    }

    public <K, V> Map<K, V> pipeline(Iterable<K> keys, BiFunction<P, K, Response<V>> function) {
        int size;
        if (keys != null && keys instanceof Collection) {
            size = ((Collection<K>) keys).size();
        } else {
            size = 16;
        }
        Map<K, V> result = new HashMap<>(size);
        if (keys != null) {
            Iterable<List<K>> partition = Iterables.partition(keys, PARTITION_SIZE);
            for (List<K> list : partition) {
                Object pool = poolFactory.get();
                try (J jedis = getJedis(pool)) {
                    P pipeline = pipeline(jedis);
                    Map<K, Response<V>> thisMap = new HashMap<>(list.size());
                    for (K key : list) {
                        Response<V> apply = function.apply(pipeline, key);
                        thisMap.put(key, apply);
                    }
                    syncPipeline(pipeline);
                    for (Entry<K, Response<V>> entry : thisMap.entrySet()) {
                        if (entry.getValue() != null) {
                            result.put(entry.getKey(), entry.getValue().get());
                        }
                    }
                } catch (Throwable e) {

                } finally {

                }
            }
        }
        return result;
    }

    public <K, V, T> Map<K, T> pipeline(Iterable<K> keys, BiFunction<P, K, Response<V>> function,
            Function<V, T> decoder) {
        int size;
        if (keys != null && keys instanceof Collection) {
            size = ((Collection<K>) keys).size();
        } else {
            size = 16;
        }
        Map<K, T> result = new HashMap<>(size);
        if (keys != null) {
            Iterable<List<K>> partition = Iterables.partition(keys, PARTITION_SIZE);
            for (List<K> list : partition) {
                Object pool = poolFactory.get();
                try (J jedis = getJedis(pool)) {
                    P pipeline = pipeline(jedis);
                    Map<K, Response<V>> thisMap = new HashMap<>(list.size());
                    for (K key : list) {
                        Response<V> apply = function.apply(pipeline, key);
                        thisMap.put(key, apply);
                    }
                    syncPipeline(pipeline);
                    for (Entry<K, Response<V>> entry : thisMap.entrySet()) {
                        if (entry.getValue() != null) {
                            result.put(entry.getKey(), decoder.apply(entry.getValue().get()));
                        }
                    }
                } catch (Throwable e) {

                } finally {

                }
            }
        }
        return result;
    }

    public JedisCommands getCommands() {
        return (JedisCommands) Proxy.newProxyInstance(jedisType.getClassLoader(),
                jedisType.getInterfaces(), new PoolableJedisCommands());
    }

    public BinaryJedisCommands getBinaryCommands() {
        return (BinaryJedisCommands) Proxy.newProxyInstance(binaryJedisType.getClassLoader(),
                binaryJedisType.getInterfaces(), new PoolableJedisCommands());
    }

    private final class PoolableJedisCommands implements InvocationHandler {

        /* (non-Javadoc)
         * @see java.lang.reflect.InvocationHandler#invoke(java.lang.Object, java.lang.reflect.Method, java.lang.Object[])
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String jedisInfo = null;
            Object pool = poolFactory.get();
            try (J jedis = getJedis(pool)) {
                if (jedis instanceof Jedis) {
                    jedisInfo = ((Jedis) jedis).getClient().getHost() + ":"
                            + ((Jedis) jedis).getClient().getPort();
                }
                Object invoke = method.invoke(jedis, args);
                return invoke;
            } catch (Throwable e) {
                exceptionHandler.accept(pool, e);
                logger.error("fail to exec jedis command, pool:{}", jedisInfo, e);
                throw e;
            }
        }
    }

    private void syncPipeline(P pipeline) {
        if (pipeline instanceof Pipeline) {
            ((Pipeline) pipeline).sync();
        } else if (pipeline instanceof ShardedJedisPipeline) {
            ((ShardedJedisPipeline) pipeline).sync();
        }
    }

    private J getJedis(Object pool) {
        if (pool instanceof Pool) {
            return ((Pool<J>) pool).getResource();
        } else {
            throw new IllegalArgumentException("invalid pool:" + pool);
        }
    }

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
        return getShardBit(Collections.singleton(bit), keyPrefix, keyHashRange).getOrDefault(bit,
                false);
    }

    public Map<Long, Boolean> getShardBit(Collection<Long> bits, String keyPrefix,
            int keyHashRange) {
        return pipeline(bits, (p, bit) -> p.getbit(getShardBitKey(bit, keyPrefix, keyHashRange),
                bit % keyHashRange));
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

    public long getShardBitCount(String keyPrefix, int keyHashRange, long start, long end) {
        return generateKeys(keyPrefix, keyHashRange, start, end).values().stream()
                .mapToLong(getCommands()::bitcount).sum();
    }

    public boolean setShardBit(long bit, String keyPrefix, int keyHashRange) {
        return setShardBit(Collections.singleton(bit), keyPrefix, keyHashRange).get(bit);
    }

    public boolean setShardBit(long bit, String keyPrefix, int keyHashRange, boolean value) {
        return setShardBitSet(Collections.singleton(bit), keyPrefix, keyHashRange, value).get(bit);
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
        allKeys.values().stream().forEach(getCommands()::del);
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
        byte[] bytes = getBinaryCommands().get(entry.getValue().getBytes());
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

    public static final class Builder<P extends PipelineBase, J extends Closeable> {

        private Supplier<Object> poolFactory;
        private BiConsumer<Object, Throwable> exceptionHandler;

        private Class<?> jedisType;
        private Class<?> binaryJedisType;

        public Builder<P, J> withExceptionHandler(BiConsumer<Object, Throwable> exceptionHandler) {
            this.exceptionHandler = exceptionHandler;
            return this;
        }

        public JedisHelper<P, J> build() {
            return new JedisHelper<>(poolFactory, exceptionHandler, jedisType, binaryJedisType);
        }
    }

    public static final Builder<ShardedJedisPipeline, ShardedJedis> newShardedBuilder(
            Supplier<ShardedJedisPool> poolFactory) {
        Builder<ShardedJedisPipeline, ShardedJedis> builder = new Builder<>();
        builder.poolFactory = (Supplier) poolFactory;
        builder.jedisType = ShardedJedis.class;
        builder.binaryJedisType = BinaryShardedJedis.class;
        return builder;
    }

    public static final Builder<Pipeline, Jedis> newBuilder(Supplier<JedisPool> poolFactory) {
        Builder<Pipeline, Jedis> builder = new Builder<>();
        builder.poolFactory = (Supplier) poolFactory;
        builder.jedisType = Jedis.class;
        builder.binaryJedisType = BinaryJedis.class;
        return builder;
    }
}