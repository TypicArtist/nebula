package net.typicartist.nebula;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class EventBusTest {

    private EventBus bus;

    @BeforeEach
    public void setUp() {
        bus = new EventBus();
    }

    public static class TestEvent {
        private final String message;

        public TestEvent(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class CancellableTestEvent implements ICancellable {
        private final String message;
        private boolean cancelled = false;

        public CancellableTestEvent(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    public static class TestSubscriber {
        public String receivedMessage;

        @Subscriber(priority = EventPriority.HIGHEST)
        public void onHighPriorityEvent(TestEvent event) {
            this.receivedMessage = event.getMessage();
        }
    }

    public static class TestInterceptor implements IEventInterceptor {
        public List<String> log = new ArrayList<>();

        public <T> void intercept(T event, IInterceptorChain chain) {
            log.add("before");
            chain.process(event);
            log.add("after");
        }

        public <T> void onError(T event, Throwable throwable) {
            log.add("error");
        }
    }

    @Test
    public void testSubscriberReceivesEvent() {
        TestSubscriber subscriber = new TestSubscriber();
        bus.register(subscriber);

        TestEvent event = new TestEvent("hello!");
        bus.post(event);

        assertEquals("hello!", subscriber.receivedMessage);
    }

    @Test
    public void testEventIsCancelled() {
        bus.register(CancellableTestEvent.class, e -> e.cancel(), EventPriority.NORMAL);

        final boolean[] called = {false};
        bus.register(CancellableTestEvent.class, e -> called[0] = true, EventPriority.LOW);

        bus.post(new CancellableTestEvent("should be cancelled"));

        assertFalse(called[0], "Low priority handler should not be called due to cancellation");
    }

    @Test
    public void testEventConsumerReceivesEvent() {
        final String[] result = {null};
        bus.register(TestEvent.class, e -> result[0] = e.getMessage(), EventPriority.NORMAL);

        bus.post(new TestEvent("via consumer"));

        assertEquals("via consumer", result[0]);
    }

    @Test
    public void testSubscriberAndConsumerTogether() {
        TestSubscriber subscriber = new TestSubscriber();
        final String[] consumerResult = {null};

        bus.register(subscriber);
        bus.register(TestEvent.class, e -> consumerResult[0] = "consumer: " + e.getMessage(), EventPriority.LOW);

        bus.post(new TestEvent("together"));

        assertEquals("together", subscriber.receivedMessage, "Subscriber should receive event");
        assertEquals("consumer: together", consumerResult[0], "Consumer should also receive event");
    }

    @Test
    public void testPriorityOrder() {
        List<String> callOrder = new ArrayList<>();

        bus.register(TestEvent.class, e -> callOrder.add("LOWEST"), EventPriority.LOWEST);
        bus.register(TestEvent.class, e -> callOrder.add("LOW"), EventPriority.LOW);
        bus.register(TestEvent.class, e -> callOrder.add("NORMAL"), EventPriority.NORMAL);
        bus.register(TestEvent.class, e -> callOrder.add("HIGH"), EventPriority.HIGH);
        bus.register(TestEvent.class, e -> callOrder.add("HIGHEST"), EventPriority.HIGHEST);

        bus.post(new TestEvent("priority"));

        assertEquals(List.of("HIGHEST", "HIGH", "NORMAL", "LOW", "LOWEST"), callOrder,
                "Handlers should be called in order of their priority from highest to lowest");
    }

    @Test
    public void testUtilMethods() {
        TestSubscriber subscriber = new TestSubscriber();

        bus.register(subscriber);
        assertEquals(true, bus.hasSubscribers(TestEvent.class));

        bus.unregister(subscriber);
        assertEquals(false, bus.hasSubscribers(TestEvent.class));
        assertEquals(List.of(), bus.getSubscribers(TestEvent.class));
    }

    @Test
    void testInterceptorOrder() {
        TestInterceptor interceptor = new TestInterceptor();
        bus.addInterceptor(interceptor);

        AtomicBoolean called = new AtomicBoolean(false);
        bus.register(TestEvent.class, event -> called.set(true), EventPriority.NORMAL);

        bus.post(new TestEvent("interceptor order test"));

        assertEquals(true, called.get());
        assertEquals(2, interceptor.log.size());
        assertEquals("before", interceptor.log.get(0));
        assertEquals("after", interceptor.log.get(1));
    }

    @Test
    void testInterceptorError() {
        TestInterceptor interceptor = new TestInterceptor();
        bus.addInterceptor(interceptor);

        bus.register(TestEvent.class, event -> { throw new RuntimeException("fail"); }, EventPriority.NORMAL);

        bus.post(new TestEvent("interceptor error test"));

        assertEquals(true, interceptor.log.contains("error"));
    }

    @Test
    void testOnceSubscriber() {
        bus.registerOnce(TestEvent.class, e -> {}, EventPriority.NORMAL);

        assertEquals(true, bus.hasSubscribers(TestEvent.class));
        bus.post(new TestEvent("once event handle"));

        assertEquals(false, bus.hasSubscribers(TestEvent.class));
    }
}
