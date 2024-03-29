package com.kt.edu.thirdproject.employee.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import reactor.cache.CacheFlux;
import reactor.cache.CacheMono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

import java.util.List;
import java.util.function.Supplier;


@Component
public class ReactiveCacheManager {
    private CacheManager cacheManager;

    @Autowired
    public ReactiveCacheManager(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public <T> Mono<T> findCachedMono(String cacheName, Object key, Supplier<Mono<T>> retriever, Class<T> classType) {
        Cache cache = cacheManager.getCache(cacheName);
        return CacheMono
                .lookup(k -> {
                    T result = cache.get(k, classType);
                    return Mono.justOrEmpty(result).map(Signal::next);
                }, key)
                .onCacheMissResume(Mono.defer(retriever))// retriever(원 메소드)의 수행을 지연시키기 위해 defer()로 감쌌음
                .andWriteWith((k, signal) -> Mono.fromRunnable(() -> {
                    if (!signal.isOnError()) {
                        cache.put(k, signal.get());
                    }
                }));
    }

    public <T> Flux<T> findCachedFlux(String cacheName, Object key, Supplier<Flux<T>> retriever) {
        Cache cache = cacheManager.getCache(cacheName);
        return CacheFlux
                .lookup(k -> {
                    List<T> result = cache.get(k, List.class);
                    return Mono.justOrEmpty(result)
                            .flatMap(list -> Flux.fromIterable(list).materialize().collectList());
                }, key)
                .onCacheMissResume(Flux.defer(retriever))
                .andWriteWith((k, signalList) -> Flux.fromIterable(signalList)
                        .dematerialize()
                        .collectList()
                        .doOnNext(list -> {
                            cache.put(k, list);
                        })
                        .then());
    }
}