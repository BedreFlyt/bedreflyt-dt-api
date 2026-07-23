package no.uio.bedreflyt.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
@EnableCaching
open class CacheConfig : CachingConfigurer {

    private val log = LoggerFactory.getLogger(CacheConfig::class.java)

    @Bean
    open fun redisConnectionFactory(
        @Value("\${spring.redis.host:localhost}") host: String,
        @Value("\${spring.redis.port:6379}") port: Int
    ): RedisConnectionFactory {
        val configuration = RedisStandaloneConfiguration(host, port)

        // Add this logging to debug the connection values
        println("Redis connecting to: $host:$port")

        return LettuceConnectionFactory(configuration)
    }

    private fun redisObjectMapper(): ObjectMapper {
        val ptv = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Any::class.java)
            .build()
        return ObjectMapper()
            .registerKotlinModule()
            .activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.EVERYTHING)
    }

    @Bean
    open fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> {
        val template = RedisTemplate<String, Any>()
        template.setConnectionFactory(connectionFactory)
        template.keySerializer = StringRedisSerializer()
        template.valueSerializer = GenericJackson2JsonRedisSerializer(redisObjectMapper())
        return template
    }

    @Bean
    open fun cacheManager(connectionFactory: RedisConnectionFactory): CacheManager {
        val jsonSerializer = GenericJackson2JsonRedisSerializer(redisObjectMapper())
        val serializationPair = RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer)
        val cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeValuesWith(serializationPair)
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(cacheConfig)
            .build()
    }

    override fun errorHandler(): CacheErrorHandler = object : CacheErrorHandler {
        override fun handleCacheGetError(e: RuntimeException, cache: Cache, key: Any) {
            log.warn("Cache GET error on '{}' key='{}': {} – treating as cache miss", cache.name, key, e.message)
        }
        override fun handleCachePutError(e: RuntimeException, cache: Cache, key: Any, value: Any?) {
            log.warn("Cache PUT error on '{}' key='{}': {}", cache.name, key, e.message)
        }
        override fun handleCacheEvictError(e: RuntimeException, cache: Cache, key: Any) {
            log.warn("Cache EVICT error on '{}' key='{}': {}", cache.name, key, e.message)
        }
        override fun handleCacheClearError(e: RuntimeException, cache: Cache) {
            log.warn("Cache CLEAR error on '{}': {}", cache.name, e.message)
        }
    }
}