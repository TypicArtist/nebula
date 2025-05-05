package net.typicartist.nebula;

public interface IInterceptorChain {
   <T> void process(T event);     
}
