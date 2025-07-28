package net.typicartist.nebula.consumer;

@FunctionalInterface
public interface IEventConsumer<T> {
    void accept(T event);
}
