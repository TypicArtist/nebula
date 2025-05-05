package net.typicartist.nebula;

public interface IEventConsumer<T> {
    void accept(T event);
}
