package net.typicartist.nebula;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class EventBus implements IEventBus {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private final Map<Class<?>, Set<IEventHandler>> eventHandlers = new ConcurrentHashMap<>();
    private final List<IEventInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public EventBus() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public <T> Future<?> postAsync(T event) {
        return executor.submit(() -> post(event));
    }

    public <T> void postBatch(List<T> events) {
        for (T event : events) {
            post(event);
        }
    }

    public <T> void post(T event) {
        IInterceptorChain chain = new InterceptorChainImpl(interceptors.iterator());
        
        try {
            chain.process(event);
        } catch (Throwable throwable) {
            for (IEventInterceptor interceptor : interceptors) {
                try {
                    interceptor.onError(event, throwable);
                } catch (Throwable ignored) {
                    ignored.printStackTrace();
                };
            }
        }
    }

    public <T> void registerOnce(Class<T> eventType, IEventConsumer<T> consumer, EventPriority priority) {
        IEventConsumer<T> onceWrapper = new IEventConsumer<>() {
            @Override
            public void accept(T event) {
                consumer.accept(event);
                unregister(this);
            }
        };
        register(eventType, onceWrapper, priority);
    }

    public <T> void register(Class<T> eventType, IEventConsumer<T> consumer, EventPriority priority) {
        eventHandlers
            .computeIfAbsent(eventType, k -> ConcurrentHashMap.newKeySet())
            .add(new EventHandlerImpl(consumer, priority.getValue()));
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

                EventPriority priority = annotation.priority();

                try {
                    MethodHandle handle = LOOKUP.unreflect(method);

                    IEventConsumer<Object> consumer = new IEventConsumer<>() {
                        @Override
                        public void accept(Object event) {
                            try {
                                handle.bindTo(subscriber).invoke(event);
                            } catch (Throwable throwable) {
                                throw new RuntimeException("Error while invoking event handler", throwable);
                            }
                        }    
                    };

                    eventHandlers
                        .computeIfAbsent(eventType, k -> ConcurrentHashMap.newKeySet())
                        .add(new EventHandlerImpl(subscriber, handle, priority.getValue()));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void unregister(Object subscriber) {
        for (Set<IEventHandler> handlers : eventHandlers.values()) {
            Iterator<IEventHandler> iterator = handlers.iterator();
            while (iterator.hasNext()) {
                IEventHandler handler = iterator.next();
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
        for (Set<IEventHandler> handlers : eventHandlers.values()) {
            for (IEventHandler handler : handlers) {
                if (handler.getSubscriber() == subscriber) {
                    handler.setActive(active);
                }
            }
        }
    }

    public void addInterceptor(IEventInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "interceptor must not be null"));
    }

    public void removeInterceptor(IEventInterceptor interceptor) {
        interceptors.remove(Objects.requireNonNull(interceptor, "interceptor must not be null"));
    }

    public <T> boolean hasSubscribers(Class<T> eventType) {
        Set<IEventHandler> handlers = eventHandlers.get(eventType);
        return handlers != null && !handlers.isEmpty();
    }

    public <T> int countSubscribers(Class<T> eventType) {
        Set<IEventHandler> handlers = eventHandlers.get(eventType);
        return hasSubscribers(eventType) ? handlers.size() : 0;
    }

    public <T> List<Object> getSubscribers(Class<T> eventType) {
        List<Object> result = new ArrayList<>();

        Set<IEventHandler> handlers = eventHandlers.get(eventType);

        if (hasSubscribers(eventType)) {
            for (IEventHandler handler : handlers) {
                if (handler.isActive()) {
                    result.add(handler.getSubscriber());
                }
            }
        };

        return result;
    }

    public void shutdown() {
        executor.shutdown();
    }

    private class InterceptorChainImpl implements IInterceptorChain {
        private final Iterator<IEventInterceptor> iterator;

        public <T> InterceptorChainImpl(Iterator<IEventInterceptor> iterator) {
            this.iterator = iterator;
        }

        public <T> void process(T event) {
            if (iterator.hasNext()) {
                IEventInterceptor next = iterator.next();
                next.intercept(event, this);
            } else {
                postToHandlers(event);
            }
        }   


        public <T> void postToHandlers(T event) {
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
                Set<IEventHandler> handlers = eventHandlers.get(eventType);
                if (hasSubscribers(eventType)) {
                    PriorityQueue<IEventHandler> queue = new PriorityQueue<>(
                        Comparator.comparingInt(handler -> -handler.getPriority())
                    );

                    for (IEventHandler handler : handlers) {
                        if (handler.isActive()) {
                            queue.add(handler);
                        }
                    }

                    while (!queue.isEmpty()) {
                        IEventHandler handler = queue.poll();
                        handler.invoke(event);

                        if (event instanceof ICancellable cancellable && cancellable.isCancelled()) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private class EventHandlerImpl implements IEventHandler {
        private final Object subscriber;
        private final MethodHandle handle;
        private final IEventConsumer<Object> consumer;
        private final int priority;
        private boolean active = true;

        public EventHandlerImpl(Object subscriber, MethodHandle handle, int priority) {
            this.subscriber = subscriber;
            this.handle = handle;
            this.consumer = null;
            this.priority = priority;
        }

        @SuppressWarnings("unchecked")
        public EventHandlerImpl(IEventConsumer<?> consumer, int priority) {
            this.subscriber = consumer;
            this.handle = null;
            this.consumer = (IEventConsumer<Object>) consumer;
            this.priority = priority;
        }

        public Object getSubscriber() {
            return subscriber;
        }

        public int getPriority() {
            return priority;
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
                throw new RuntimeException("Error while invoking event handler", throwable);
            }
        } 
    }
}