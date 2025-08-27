package net.typicartist.nebula;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

import net.typicartist.nebula.consumer.IEventConsumer;
import net.typicartist.nebula.handler.IEventHandler;

/**
 * A simple and extensible event bus implementation to register, unregister,
 * and post events to subscribers with prioritized event handling.
 * <p>
 * Supports synchronous event posting and prioritized event consumers.
 * Also supports one-time event listeners and cancellable events.
 * </p>
 * <p>
 * Subscribers can be registered by method annotations using {@link Subscriber}.
 * Event handlers can be registered manually by providing event type and consumer.
 */
public class EventBus implements IEventBus {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private final Map<Class<?>, Set<IEventHandler>> eventHandlers = new ConcurrentHashMap<>();
    private final Map<Class<?>, Set<Class<?>>> hierarchyCache = new ConcurrentHashMap<>();

    /**
     * Posts an event synchronously to all registered subscribers of the event's
     * class or its superclasses/interfaces, respecting the handler priority.
     * 
     * If the event implements {@link ICancellable} and is cancelled by any handler,
     * further handlers will not be invoked.
     * 
     * @param <T> the event type
     * @param event the event instance to post
     */
    @Override
    public <T> void post(T event) {
        Set<Class<?>> eventClasses = resolveHierarchy(event.getClass());

        for (Class<?> type : eventClasses) {
            Set<IEventHandler> handlers = eventHandlers.get(type);
            if (handlers == null || handlers.isEmpty()) continue;

            for (IEventHandler handler : handlers) {
                if (!handler.isActive()) continue;

                handler.invoke(event, () -> {
                    if (handler.isOnce()) {
                        Set<IEventHandler> set = eventHandlers.get(type);
                        if (set != null) set.remove(handler);
                    }
                });

                if (event instanceof ICancellable cancellable &&  cancellable.isCancelled()) return;
            }
        }
    }
    
    /**
     * Registers an event consumer for a specific event type with given priority and once-flag.
     * 
     * @param <T> the event type
     * @param eventType the class of the event to listen for
     * @param consumer the consumer callback to invoke when the event is posted
     * @param priority the priority of this handler relative to others (higher runs first)
     * @param once if true, the handler is automatically unregistered after first invocation
     */
    @Override
    public <T> void register(Class<T> eventType, IEventConsumer<T> consumer, EventPriority priority, boolean once) {
        addHandler(null, eventType, consumer, priority, once);
    }

    @SuppressWarnings("unchecked")
    private <T> void addHandler(Object subscriber, Class<T> type, IEventConsumer<T> consumer, EventPriority priority, boolean once) {
        IEventHandler handler = new EventHandlerImpl(subscriber, (IEventConsumer<Object>) consumer, priority.getValue(), once);
        eventHandlers.computeIfAbsent(type, k -> new ConcurrentSkipListSet<>()).add(handler);
    }
    
    /**
     * Registers all methods annotated with {@link Subscriber} in the given subscriber object.
     * 
     * Methods must have a single parameter of the event type and return void.
     * 
     * @param subscriber the object containing subscriber methods
     */
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

    /**
     * Unregisters all event handlers associated with the given subscriber object.
     * 
     * @param subscriber the subscriber to unregister
     */
    @Override
    public void unregister(Object subscriber) {
        for (Set<IEventHandler> handlers : eventHandlers.values()) {
            handlers.removeIf(h -> h instanceof EventHandlerImpl impl && impl.matchesSubscriber(subscriber));
        }
    }

    /**
     * Marks all event handlers of the subscriber as active to receive events.
     * 
     * @param subscriber the subscriber to activate
     */
    @Override
    public void subscribe(Object subscriber) {
        setActive(subscriber, true);
    }

    /**
     * Marks all event handlers of the subscriber as inactive to temporarily stop receiving events.
     * 
     * @param subscriber the subscriber to deactivate
     */
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
   
    /**
     * Returns whether there are any subscribers registered for the given event type.
     *
     * @param <T> the event type
     * @param eventType the event class
     * @return true if there are any subscribers, false otherwise
     */
    @Override
    public <T> boolean hasSubscribers(Class<T> eventType) {
        Set<IEventHandler> handlers = eventHandlers.get(eventType);
        return handlers != null && !handlers.isEmpty();
    }

    /**
     * Returns the count of subscribers registered for the given event type.
     * 
     * @param <T> the event type
     * @param eventType the event class
     * @return number of subscribers
     */
    @Override
    public <T> int countSubscribers(Class<T> eventType) {
        Set<IEventHandler> handlers = eventHandlers.get(eventType);
        return hasSubscribers(eventType) ? handlers.size() : 0;
    }

    /**
     * Returns a list of subscriber identities currently active for the given event type.
     * 
     * @param <T> the event type
     * @param eventType the event class
     * @return list of active subscriber objects
     */
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

    /**
     * Resolves and caches the full class hierarchy (superclasses and interfaces)
     * for the given event class, to enable posting events to all relevant handlers.
     * @param clazz the event class
     * @return set of classes and interfaces in the hierarchy
     */
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

    /**
     * Internal implementation of an event handler wrapping an event consumer.
     * Supports activation state and one-time invocation semantics.
     */
    private static class EventHandlerImpl implements IEventHandler, Comparable<EventHandlerImpl> {
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

        @Override
        public int compareTo(EventHandlerImpl other) {
            int cmp = Integer.compare(other.priority, this.priority);
            if (cmp == 0) {
                return Integer.compare(System.identityHashCode(this.identity), System.identityHashCode(other.identity));
            }
            return cmp;
        }
    }
}