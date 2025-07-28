package net.typicartist.nebula;

import java.util.List;

import net.typicartist.nebula.consumer.IEventConsumer;

public interface IEventBus {
    <T> void post(T event);
    <T> void register(Class<T> eventType, IEventConsumer<T> consumer, EventPriority priority);
    void register(Object subscriber) ;
    <T> void registerOnce(Class<T> eventType, IEventConsumer<T> consumer, EventPriority priority);
    void unregister(Object subscriber);
    void subscribe(Object subscriber);
    void unsubscribe(Object subscriber);
    <T> boolean hasSubscribers(Class<T> eventType);
    <T> int countSubscribers(Class<T> eventType);
    <T> List<Object> getSubscribers(Class<T> eventType);
}