package cn.dreampie.cache.redis;

import cn.dreampie.cache.CacheEvent;
import cn.dreampie.cache.CacheException;
import cn.dreampie.cache.CacheManager;
import cn.dreampie.common.Constant;
import cn.dreampie.common.util.properties.Prop;
import cn.dreampie.common.util.properties.Proper;
import cn.dreampie.common.util.serialize.Serializer;
import cn.dreampie.log.Logger;
import org.apache.commons.pool2.impl.BaseObjectPoolConfig;
import redis.clients.jedis.*;
import redis.clients.util.Pool;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Created by Dreampie on 15/4/24.
 */
public class RedisManager extends CacheManager {

  private static final Logger logger = Logger.getLogger(RedisManager.class);

  private final Jedis jedis;
  private final ShardedJedis shardedJedis;

  private static final Pool pool;
  private static final String host;
  private static final int timeout;

  static {
    Prop config = null;
    try {
      config = Proper.use("redis.properties");
    } catch (Exception e) {
      logger.warn(e.getMessage());
    }
    if (config != null) {

      String shardHost = config.get("redis.shard.host");
      String[] hp;
      host = config.get("redis.host");
      timeout = config.getInt("redis.timeout", Protocol.DEFAULT_TIMEOUT);
      JedisPoolConfig poolConfig = new JedisPoolConfig();
      poolConfig.setLifo(config.getBoolean("redis.pool.lifo", BaseObjectPoolConfig.DEFAULT_LIFO));
      poolConfig.setMaxWaitMillis(config.getLong("redis.pool.maxWaitMillis", BaseObjectPoolConfig.DEFAULT_MAX_WAIT_MILLIS));
      poolConfig.setMinEvictableIdleTimeMillis(config.getLong("redis.pool.minEvictableIdleTimeMillis", BaseObjectPoolConfig.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS));
      poolConfig.setSoftMinEvictableIdleTimeMillis(config.getLong("redis.pool.softMinEvictableIdleTimeMillis", BaseObjectPoolConfig.DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME_MILLIS));
      poolConfig.setNumTestsPerEvictionRun(config.getInt("redis.pool.numTestsPerEvictionRun", BaseObjectPoolConfig.DEFAULT_NUM_TESTS_PER_EVICTION_RUN));
      poolConfig.setTestOnBorrow(config.getBoolean("redis.pool.testOnBorrow", BaseObjectPoolConfig.DEFAULT_TEST_ON_BORROW));
      poolConfig.setTestOnReturn(config.getBoolean("redis.pool.testOnReturn", BaseObjectPoolConfig.DEFAULT_TEST_ON_RETURN));
      poolConfig.setTestWhileIdle(config.getBoolean("redis.pool.testWhileIdle", BaseObjectPoolConfig.DEFAULT_TEST_WHILE_IDLE));
      poolConfig.setTimeBetweenEvictionRunsMillis(config.getLong("redis.pool.timeBetweenEvictionRunsMillis", BaseObjectPoolConfig.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS));
      poolConfig.setEvictionPolicyClassName(config.get("redis.pool.evictionPolicyClassName", BaseObjectPoolConfig.DEFAULT_EVICTION_POLICY_CLASS_NAME));
      poolConfig.setBlockWhenExhausted(config.getBoolean("redis.pool.blockWhenExhausted", BaseObjectPoolConfig.DEFAULT_BLOCK_WHEN_EXHAUSTED));
      poolConfig.setJmxEnabled(config.getBoolean("redis.pool.jmxEnabled", BaseObjectPoolConfig.DEFAULT_JMX_ENABLE));
      poolConfig.setJmxNamePrefix(config.get("redis.pool.jmxNamePrefix", BaseObjectPoolConfig.DEFAULT_JMX_NAME_PREFIX));

      if (shardHost != null) {
        String[] shards = shardHost.split(",");
        List<JedisShardInfo> shardInfos = new ArrayList<JedisShardInfo>();
        JedisShardInfo shardInfo;
        for (String s : shards) {
          hp = s.split(":");
          shardInfo = new JedisShardInfo(hp[0], Integer.parseInt(hp[1]), timeout);
          shardInfos.add(shardInfo);
        }
        pool = new ShardedJedisPool(poolConfig, shardInfos);
      } else {
        if (host != null) {
          hp = host.split(":");
          pool = new JedisPool(poolConfig, hp[0], Integer.parseInt(hp[1]));
        } else {
          pool = null;
          logger.error("Could not found 'redis.host' or 'redis.shard.host'");
        }
      }
    } else {
      host = "127.0.0.1:6379";
      pool = null;
      timeout = Protocol.DEFAULT_TIMEOUT;
    }
  }

  private Type type;

  public RedisManager() throws CacheException {
    try {
      if (pool != null) {
        if (pool instanceof ShardedJedisPool) {
          this.jedis = null;
          this.shardedJedis = (ShardedJedis) pool.getResource();
        } else {
          this.jedis = (Jedis) pool.getResource();
          this.shardedJedis = null;
        }
      } else {
        String[] hp = host.split(":");
        this.jedis = new Jedis(hp[0], Integer.parseInt(hp[1]), timeout);
        this.shardedJedis = null;
      }
    } catch (Exception e) {
      throw new CacheException(e);
    }
  }

  private String getRedisKey(String group, String key) {
    return group + Constant.CONNECTOR + key;
  }

  public <T> T getCache(String group, String key) {
    String jkey = getRedisKey(group, key);
    if (shardedJedis != null) {
      return (T) Serializer.unserialize(shardedJedis.get(jkey.getBytes()));
    } else {
      return (T) Serializer.unserialize(jedis.get(jkey.getBytes()));
    }
  }

  public void addCache(String group, String key, Object cache) {
    String jkey = getRedisKey(group, key);
    if (shardedJedis != null) {
      shardedJedis.set(jkey.getBytes(), Serializer.serialize(cache));
    } else {
      jedis.set(jkey.getBytes(), Serializer.serialize(cache));
    }
  }

  public void removeCache(String group, String key) {
    String jkey = getRedisKey(group, key);
    if (shardedJedis != null) {
      shardedJedis.del(jkey.getBytes());
    } else {
      jedis.del(jkey.getBytes());
    }
  }

  public void doFlush(CacheEvent event) {
    if (event.getType().equals(CacheEvent.CacheEventType.ALL)) {
      if (shardedJedis != null) {
        Collection<Jedis> shards = shardedJedis.getAllShards();
        for (Jedis jedis : shards) {
          jedis.flushDB();
        }

      } else {
        jedis.flushDB();
      }
    } else if (event.getType().equals(CacheEvent.CacheEventType.GROUP)) {
      if (shardedJedis != null) {
        Collection<Jedis> shards = shardedJedis.getAllShards();
        for (Jedis jedis : shards) {
          delGroup(jedis, event);
        }
      } else {
        delGroup(jedis, event);
      }
    }
  }

  private void delGroup(Jedis jedis, CacheEvent event) {
    Set<String> keySet = jedis.keys(event.getGroup() + Constant.CONNECTOR + '*');
    if (keySet != null && keySet.size() > 0) {
      String[] keys = new String[keySet.size()];
      jedis.del(keySet.toArray(keys));
    }
  }

}
