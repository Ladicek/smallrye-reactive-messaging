package io.smallrye.reactive.messaging.kafka.impl;

import static io.smallrye.reactive.messaging.kafka.i18n.KafkaLogging.log;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.reactivestreams.Subscription;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.Subscriptions;
import io.smallrye.mutiny.operators.AbstractMulti;
import io.smallrye.mutiny.subscription.MultiSubscriber;
import io.smallrye.reactive.messaging.kafka.KafkaConnectorIncomingConfiguration;
import io.vertx.core.Context;

public class KafkaRecordStream<K, V> extends AbstractMulti<ConsumerRecord<K, V>> {

    private final ReactiveKafkaConsumer<K, V> client;
    private final KafkaConnectorIncomingConfiguration config;
    private final Context context;

    public KafkaRecordStream(ReactiveKafkaConsumer<K, V> client,
            KafkaConnectorIncomingConfiguration config, Context context) {
        this.config = config;
        this.client = client;
        this.context = context;
    }

    @Override
    public void subscribe(
            MultiSubscriber<? super ConsumerRecord<K, V>> subscriber) {
        KafkaRecordStreamSubscription subscription = new KafkaRecordStreamSubscription(client, config, subscriber);
        subscriber.onSubscribe(subscription);
    }

    private class KafkaRecordStreamSubscription implements Subscription {
        private final ReactiveKafkaConsumer<K, V> client;
        private final MultiSubscriber<? super ConsumerRecord<K, V>> downstream;
        private final boolean pauseResumeEnabled;

        private final AtomicInteger wip = new AtomicInteger();
        /**
         * Stores the current downstream demands.
         */
        private final AtomicLong requested = new AtomicLong();

        /**
         * Started flag.
         */
        private final AtomicBoolean started = new AtomicBoolean();

        /**
         * Paused / Resumed flag.
         */
        private final AtomicBoolean paused = new AtomicBoolean();

        /**
         * The polling uni to avoid re-assembling a Uni everytime.
         */
        private final Uni<ConsumerRecords<K, V>> pollUni;

        private final RecordQueue<ConsumerRecord<K, V>> queue;
        private final long retries;
        private final int batchSize;
        private final int maxQueueSize;

        /**
         * {@code true} if the subscription has been cancelled.
         */
        private volatile boolean cancelled;

        public KafkaRecordStreamSubscription(
                ReactiveKafkaConsumer<K, V> client,
                KafkaConnectorIncomingConfiguration config,
                MultiSubscriber<? super ConsumerRecord<K, V>> subscriber) {
            this.client = client;
            this.pauseResumeEnabled = config.getPauseIfNoRequests();
            this.downstream = subscriber;
            this.batchSize = config.config().getOptionalValue(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.class)
                    .orElse(500);
            this.maxQueueSize = config.getMaxPollBufferSize().orElse(5 * batchSize);
            this.queue = new RecordQueue<>(2 * batchSize);
            this.retries = config.getRetryAttempts() == -1 ? Long.MAX_VALUE : config.getRetryAttempts();
            this.pollUni = client.poll()
                    .onItem().transform(cr -> {
                        if (cr.isEmpty()) {
                            pauseResume();
                            return null;
                        }
                        queue.addAll(cr);
                        pauseResume();
                        return cr;
                    })
                    .plug(m -> {
                        if (config.getRetry()) {
                            int maxWait = config.getRetryMaxWait();
                            return m.onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(maxWait))
                                    .atMost(retries);
                        }
                        return m;
                    });
        }

        private void pauseResume() {
            if (pauseResumeEnabled) {
                long requested = this.requested.get();
                int queueSize = this.queue.size();
                if (!paused.get() && queueSize > 0) {
                    log.infof("running, queued %s", queueSize);
                }
                if ((requested == 0 || queueSize + this.batchSize > this.maxQueueSize) && paused.compareAndSet(false, true)) {
                    log.infof("requested %s, queued %s, pausing", requested, queueSize);
                    log.pausingChannel(config.getChannel());
                    client.pause()
                            .subscribe().with(x -> {
                            }, this::report);
                } else if ((requested > 0 && queueSize < this.maxQueueSize / 2) && paused.compareAndSet(true, false)) {
                    log.infof("requested %s, queued %s, resuming", requested, queueSize);
                    log.resumingChannel(config.getChannel());
                    client.resume()
                            .subscribe().with(x -> {
                            }, this::report);
                }
            }
        }

        @Override
        public void request(long n) {
            if (n > 0) {
                if (!cancelled) {
                    Subscriptions.add(requested, n);
                    if (started.compareAndSet(false, true) && !cancelled) {
                        poll();
                    } else if (!cancelled) {
                        dispatch();
                    }
                    pauseResume();
                }
            } else {
                throw new IllegalArgumentException("Invalid request");
            }

        }

        private void poll() {
            if (cancelled || client.isClosed()) {
                return;
            }

            pollUni
                    .subscribe().with(
                            cr -> {
                                if (cr == null) {
                                    client.executeWithDelay(this::poll, Duration.ofMillis(2))
                                            .subscribe().with(x -> {
                                            }, this::report);
                                } else {
                                    dispatch();
                                    client.runOnPollingThread(c -> {
                                        poll();
                                    })
                                            .subscribe().with(x -> {
                                            }, this::report);
                                }
                            },
                            this::report);
        }

        private void report(Throwable fail) {
            if (!cancelled) {
                cancelled = true;
                downstream.onFailure(fail);
            }
        }

        void dispatch() {
            if (wip.getAndIncrement() != 0) {
                return;
            }
            context.runOnContext(ignored -> run());
        }

        private void run() {
            int missed = 1;
            final RecordQueue<ConsumerRecord<K, V>> q = queue;
            long emitted = 0;
            long requests = requested.get();
            for (;;) {
                if (isCancelled()) {
                    return;
                }

                while (emitted != requests) {
                    ConsumerRecord<K, V> item = q.poll();

                    if (item == null || isCancelled()) {
                        break;
                    }

                    downstream.onItem(item);
                    emitted++;
                }

                requests = requested.addAndGet(-emitted);
                emitted = 0;

                int w = wip.get();
                if (missed == w) {
                    missed = wip.addAndGet(-missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }

        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            if (wip.getAndIncrement() == 0) {
                // nothing was currently dispatched, clearing the queue.
                client.close();
                queue.clear();
            }
        }

        boolean isCancelled() {
            if (cancelled) {
                queue.clear();
                client.close();
                return true;
            }
            return false;
        }
    }
}
