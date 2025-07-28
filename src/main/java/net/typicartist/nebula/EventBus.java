package net.typicartist.nebula;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

import net.typicartist.nebula.consumer.IEventConsumer;
import net.typicartist.nebula.handler.IEventHandler;

public class EventBus implements IEventBus {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private final Map<Class<?>, Set<IEventHandler>> eventHandlers = new ConcurrentHashMap<>();
    private final Map<Class<?>, Set<Class<?>>> hierarchyCache = new ConcurrentHashMap<>();

    @Override
    public <T> void post(T event) {
        Set<Class<?>> eventClasses = resolveHierarchy(event.getClass());

        for (Class<?> type : eventClasses) {
            Set<IEventHandler> handlers = eventHandlers.get(type);
            if (handlers == null || handlers.isEmpty()) continue;

            PriorityQueue<IEventHandler> queue = new PriorityQueue<>(Comparator.comparingInt(IEventHandler::getPriority).reversed());
            queue.addAll(handlers);

            while (!queue.isEmpty()) {
                IEventHandler handler = queue.poll();
                if (!handler.isActive()) continue;

                handler.invoke(event, () -> handlers.remove(handler));

                if (event instanceof ICancellable cancellable && cancellable.isCancelled()) {
                    return;
                }
            }
        }
    }
    
    @Override
    public <T> void register(Class<T> eventType, IEventConsumer<T> consumer, EventPriority priority, boolean once) {
        addHandler(null, eventType, consumer, priority, once);
    }

    @SuppressWarnings("unchecked")
    private <T> void addHandler(Object subscriber, Class<T> type, IEventConsumer<T> consumer, EventPriority priority, boolean once) {
        IEventHandler handler = new EventHandlerImpl(subscriber, (IEventConsumer<Object>) consumer, priority.getValue(), once);
        eventHandlers.computeIfAbsent(type, k -> ConcurrentHashMap.newKeySet()).add(handler);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public void register(Object subscriber) {
        Class<?> clazz = subscriber.getClass();
        
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Subscriber.class)) continue;
            if (method.getParameterCount() != 1 || method.getReturnType() != void.class) {
                System.err.println("Invalid subscriber method signature: " + method);
                continue;
            }

            Class<?> paramType = method.getParameterTypes()[0];
            Subscriber meta = method.getAnnotation(Subscriber.class);
            EventPriority priority = meta.priority();
            boolean once = meta.once();

            method.setAccessible(true);

            try {
                MethodHandle handle = LOOKUP.unreflect(method).bindTo(subscriber);

                IEventConsumer<Object> consumer = event -> {
                    try {
                        handle.invoke(event);
                    } catch (Throwable t) {
                        throw new RuntimeException("Error invoking event handler", t);
                    }
                };


                addHandler(subscriber, (Class<Object>) paramType, consumer, priority, once);
            } catch (IllegalAccessException e) {
                e.printStackTrace();;
            }
        }
    }

    @Override
    public void unregister(Object subscriber) {
        for (Set<IEventHandler> handlers : eventHandlers.values()) {
            handlers.removeIf(h -> h instanceof EventHandlerImpl impl && impl.matchesSubscriber(subscriber));
        }
    }

    @Override
    public void subscribe(Object subscriber) {
        setActive(subscriber, true);
    }

    @Override
    public void unsubscribe(Object subscriber) {
        setActive(subscriber, false);
    }

    private void setActive(Object subscriber, boolean active) {
        for (Set<IEventHandler> handlers : eventHandlers.values()) {
            for (IEventHandler handler : handlers) {
                if (handler.getSubscriber() == subscriber) {
                    handler.setActive(active);
                }
            }
        }
    }

    @Override
    public <T> boolean hasSubscribers(Class<T> eventType) {
        Set<IEventHandler> handlers = eventHandlers.get(eventType);
        return handlers != null && !handlers.isEmpty();
    }

    @Override
    public <T> int countSubscribers(Class<T> eventType) {
        Set<IEventHandler> handlers = eventHandlers.get(eventType);
        return hasSubscribers(eventType) ? handlers.size() : 0;
    }

    @Override
    public <T> List<Object> getSubscribers(Class<T> eventType) {
        List<Object> result = new ArrayList<>();
        Set<IEventHandler> handlers = eventHandlers.get(eventType);

        if (handlers != null) {
            for (IEventHandler handler : handlers) {
                if (handler.isActive()) {
                    result.add(handler.getSubscriber());
                }
            }
        }

        return result;
    }

    private Set<Class<?>> resolveHierarchy(Class<?> clazz) {
        return hierarchyCache.computeIfAbsent(clazz, c -> {
            Set<Class<?>> result = new LinkedHashSet<>();
            Queue<Class<?>> queue = new LinkedList<>();
            queue.add(c);
            while (!queue.isEmpty()) {
                Class<?> current = queue.poll();
                if (current == null || !result.add(current)) continue;
                queue.add(current.getSuperclass());
                queue.addAll(Arrays.asList(current.getInterfaces()));
            }
            return result;
        });
    }

    private static class EventHandlerImpl implements IEventHandler {
        private final IEventConsumer<Object> consumer;
        private final int priority;
        private final boolean once;
        private boolean active = true;
        private final Object identity;

        public EventHandlerImpl(Object subscriber, IEventConsumer<Object> consumer, int priority, boolean once) {
            this.consumer = consumer;
            this.priority = priority;
            this.once = once;
            this.identity = subscriber;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public boolean isOnce() {
            return once;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void setActive(boolean active) {
            this.active = active;
        }
        
        @Override
        public Object getSubscriber() {
            return identity;
        }
        
        @Override
        public void invoke(Object event, Runnable onRemove) {
            consumer.accept(event);
            if (once) onRemove.run();
        }

        public boolean matchesSubscriber(Object obj) {
            return identity == obj;
        }
    }
}