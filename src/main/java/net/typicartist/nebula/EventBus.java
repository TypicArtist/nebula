package net.typicartist.nebula;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class EventBus implements IEventBus {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private final Map<Class<?>, Set<EventHandler>> eventHandlers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public <T> Future<?> postAsync(T event) {
        return executor.submit(() -> post(event));
    }

    public <T> void post(T event) {
        Set<Class<?>> eventClasses = new HashSet<>();
        Class<?> eventClass = event.getClass();

        while (eventClass != Object.class) {
            eventClasses.add(eventClass);
            eventClass = eventClass.getSuperclass();
        }

        if (eventClass != null) {
            Collections.addAll(eventClasses, eventClass.getInterfaces());
        }

        for (Class<?> eventType : eventClasses) {
            Set<EventHandler> handlers = eventHandlers.get(eventType);
            if (handlers != null) {
                PriorityQueue<EventHandler> queue = new PriorityQueue<>(
                    Comparator.comparingInt(handler -> -handler.priority)
                );

                for (EventHandler handler : handlers) {
                    if (handler.isActive()) {
                        queue.add(handler);
                    }
                }

                while (!queue.isEmpty()) {
                    EventHandler handler = queue.poll();
                    handler.invoke(event);

                    if (event instanceof ICancellable cancellable && cancellable.isCancelled()) {
                        break;
                    }
                }
            }
        }
    }

    public <T> void register(Class<T> eventType, IEventConsumer<T> consumer, EventPriority priority) {
        eventHandlers
            .computeIfAbsent(eventType, k -> ConcurrentHashMap.newKeySet())
            .add(new EventHandler(consumer, priority.getValue()));
    }

    public void register(Object subscriber) {
        for (Method method : subscriber.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Subscriber.class)) {
                if (method.getReturnType() != void.class || method.getParameterCount() != 1) {
                    System.err.println("Invalid event handler method: " + method);
                    continue;
                }

                Subscriber annotation = method.getAnnotation(Subscriber.class);
                Class<?> eventType = method.getParameterTypes()[0];

                try {
                    MethodHandle handle = LOOKUP.unreflect(method);
                    eventHandlers
                        .computeIfAbsent(eventType, k -> ConcurrentHashMap.newKeySet())
                        .add(new EventHandler(subscriber, handle, annotation.priority().getValue()));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    public void unregister(Object subscriber) {
        for (Set<EventHandler> handlers : eventHandlers.values()) {
            Iterator<EventHandler> iterator = handlers.iterator();
            while (iterator.hasNext()) {
                EventHandler handler = iterator.next();
                if (handler.getSubscriber() == subscriber) {
                    iterator.remove();
                }
            }
        }
    } 

    public void subscribe(Object subscriber) {
        setActive(subscriber, true);
    }

    public void unsubscribe(Object subscriber) {
        setActive(subscriber, false);
    }

    private void setActive(Object subscriber, boolean active) {
        for (Set<EventHandler> handlers : eventHandlers.values()) {
            for (EventHandler handler : handlers) {
                if (handler.getSubscriber() == subscriber) {
                    handler.setActive(active);
                }
            }
        }
    }

    public <T> boolean hasListeners(Class<T> eventType) {
        Set<EventHandler> handlers = eventHandlers.get(eventType);

        return handlers != null && !handlers.isEmpty();
    }

    public <T> List<Object> getListeners(Class<T> eventType) {
        List<Object> result = new ArrayList<>();

        Set<EventHandler> handlers = eventHandlers.get(eventType);

        if (handlers != null) {
            for (EventHandler handler : handlers) {
                if (handler.isActive()) {
                    result.add(handler);
                }
            }
        };

        return result;
    }

    public void shutdown() {
        executor.shutdown();
    }

    private class EventHandler {
        private final Object subscriber;
        private final MethodHandle handle;
        private final IEventConsumer<Object> consumer;
        private final int priority;
        private boolean active = true;

        public EventHandler(Object subscriber, MethodHandle handle, int priority) {
            this.subscriber = subscriber;
            this.handle = handle;
            this.consumer = null;
            this.priority = priority;
        }

        @SuppressWarnings("unchecked")
        public EventHandler(IEventConsumer<?> consumer, int priority) {
            this.subscriber = consumer;
            this.handle = null;
            this.consumer = (IEventConsumer<Object>) consumer;
            this.priority = priority;
        }

        public Object getSubscriber() {
            return subscriber;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public void invoke(Object event) {
            try {
                if (consumer != null) {
                    consumer.accept(event);
                } else {
                    handle.bindTo(subscriber).invoke(event);
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();;
            }
        } 
    }
}