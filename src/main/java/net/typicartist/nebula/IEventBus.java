package net.typicartist.nebula;

public interface IEventBus {
    <T> void post(T event);
    void register(Object subscriber);
    void unregister(Object subscriber);
    void subscribe(Object subscriber);
    void unsubscribe(Object subscriber);   
}