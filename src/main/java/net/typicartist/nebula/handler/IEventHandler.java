package net.typicartist.nebula.handler;

public interface IEventHandler {
    int getPriority();
    boolean isOnce();
    boolean isActive();
    void setActive(boolean active);
    void invoke(Object event, Runnable removeCallback);
    Object getSubscriber();
}
