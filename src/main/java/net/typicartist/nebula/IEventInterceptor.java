package net.typicartist.nebula;

public interface IEventInterceptor {
    <T> void intercept(T event, IInterceptorChain chain);
    default <T> void onError(T event, Throwable throwable) {};
}