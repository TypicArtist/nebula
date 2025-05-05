package net.typicartist.nebula;

public interface IEventInterceptor {
    <T> void intercept(T event, InterceptorChain chain);
}