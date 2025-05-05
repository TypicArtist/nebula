package net.typicartist.nebula;

public interface IEventBus {
    <T> void post(T event);
    void register(Object subscriber);
    <T> void register(Class<T> eventType, IEventConsumer<T> consumer, EventPriority priority);
    void subscribe(Object subscriber);
    void unsubscribe(Object subscriber);   
}