package net.typicartist.nebula;

public interface IEventHandler {
    Object getSubscriber();
    int getPriority();
    boolean isActive();
    void setActive(boolean active);
    void invoke(Object event);  
}
