/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.JetEngineConfig;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static com.hazelcast.jet.impl.ProgressState.DONE;
import static com.hazelcast.jet.impl.ProgressState.MADE_PROGRESS;
import static com.hazelcast.jet.impl.ProgressState.NO_PROGRESS;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;

@Category(QuickTest.class)
@RunWith(HazelcastSerialClassRunner.class)
public class ExecutionServiceTest {

    @Rule
    public final ExpectedException exceptionRule = ExpectedException.none();

    ExecutionService es;

    @Before
    public void before() {
        HazelcastInstance hzMock = mock(HazelcastInstance.class);
        Mockito.when(hzMock.getName()).thenReturn("test-hz-instance");
        es = new ExecutionService(hzMock, "test-execservice", new JetEngineConfig().setParallelism(4));
    }

    @Test
    public void when_blockingTask_then_executed() {
        // Given
        final MockTasklet t = new MockTasklet().blocking();

        // When
        es.execute(singletonList(t)).toCompletableFuture().join();

        // Then
        t.assertDone();
    }

    @Test
    public void when_nonblockingTask_then_executed() {
        // Given
        final MockTasklet t = new MockTasklet();

        // When
        es.execute(singletonList(t)).toCompletableFuture().join();

        // Then
        t.assertDone();
    }

    @Test(expected = CompletionException.class)
    public void when_nonblockingAndInitFails_then_futureFails() {
        // Given
        final MockTasklet t = new MockTasklet().initFails();

        // When
        es.execute(singletonList(t)).toCompletableFuture().join();

        // Then
        t.assertDone();
    }

    @Test(expected = CompletionException.class)
    public void when_blockingAndInitFails_then_futureFails() {
        // Given
        final MockTasklet t = new MockTasklet().blocking().initFails();

        // When - Then
        es.execute(singletonList(t)).toCompletableFuture().join();
    }

    @Test(expected = CompletionException.class)
    public void when_nonblockingAndCallFails_then_futureFails() {
        // Given
        final MockTasklet t = new MockTasklet().callFails();

        // When - Then
        es.execute(singletonList(t)).toCompletableFuture().join();
    }

    @Test(expected = CompletionException.class)
    public void when_blockingAndCallFails_then_futureFails() {
        // Given
        final MockTasklet t = new MockTasklet().blocking().callFails();

        // When - Then
        es.execute(singletonList(t)).toCompletableFuture().join();
    }

    @Test
    public void when_shutdown_then_submitFails() {
        // Given
        es.execute(singletonList(new MockTasklet()));
        es.execute(singletonList(new MockTasklet()));

        // When
        es.shutdown();

        // Then
        exceptionRule.expect(IllegalStateException.class);
        es.execute(singletonList(new MockTasklet()));
    }

    @Test
    public void when_manyCallsWithSomeStalling_then_eventuallyDone() {
        // Given
        final List<MockTasklet> tasklets = asList(
                new MockTasklet().blocking().callsBeforeDone(10),
                new MockTasklet().callsBeforeDone(10));

        // When
        es.execute(tasklets).toCompletableFuture().join();

        // Then
        tasklets.forEach(MockTasklet::assertDone);
    }

    @Test
    public void when_workStealing_then_allComplete() {
        // Given
        final List<MockTasklet> tasklets =
                Stream.generate(() -> new MockTasklet().callsBeforeDone(1000))
                      .limit(100).collect(toList());

        // When
        es.execute(tasklets).toCompletableFuture().join();

        // Then
        tasklets.forEach(MockTasklet::assertDone);
    }

    @Test
    public void when_nonBlockingTaskletIsCancelled_thenCompleteEarly() throws ExecutionException, InterruptedException {
        // Given
        final List<MockTasklet> tasklets =
                Stream.generate(() -> new MockTasklet().callsBeforeDone(Integer.MAX_VALUE))
                      .limit(100).collect(toList());

        // When
        CompletableFuture<Void> future = es.execute(tasklets).toCompletableFuture();
        future.cancel(true);

        // Then
        tasklets.forEach(MockTasklet::assertNotDone);

        exceptionRule.expect(CancellationException.class);
        future.get();
    }

    @Test
    public void when_blockingTaskletIsCancelled_thenCompleteEarly() throws ExecutionException, InterruptedException {
        // Given
        final List<MockTasklet> tasklets =
                Stream.generate(() -> new MockTasklet().blocking().callsBeforeDone(Integer.MAX_VALUE))
                      .limit(100).collect(toList());

        // When
        CompletableFuture<Void> future = es.execute(tasklets).toCompletableFuture();
        future.cancel(true);

        // Then
        tasklets.forEach(MockTasklet::assertNotDone);

        exceptionRule.expect(CancellationException.class);
        future.get();
    }

    @Test
    public void when_blockingSleepingTaskletIsCancelled_thenCompleteEarly() throws ExecutionException, InterruptedException {
        // Given
        final List<MockTasklet> tasklets =
                Stream.generate(() -> new MockTasklet().sleeping().callsBeforeDone(Integer.MAX_VALUE))
                      .limit(100).collect(toList());

        // When
        CompletableFuture<Void> future = es.execute(tasklets).toCompletableFuture();
        future.cancel(true);

        // Then
        tasklets.forEach(MockTasklet::assertNotDone);
        exceptionRule.expect(CancellationException.class);
        future.get();

    }

    static class MockTasklet implements Tasklet {

        boolean isBlocking;
        boolean initFails;
        boolean callFails;
        int callsBeforeDone;

        private boolean willMakeProgress = true;
        private boolean isSleeping;

        @Override
        public boolean isBlocking() {
            return isBlocking;
        }

        @Override
        public ProgressState call() {
            if (callFails) {
                throw new RuntimeException("mock call failure");
            }
            if (isSleeping) {
                try {
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    return DONE;
                }
            }
            willMakeProgress = !willMakeProgress;
            return callsBeforeDone-- == 0 ? DONE
                    : willMakeProgress ? MADE_PROGRESS
                    : NO_PROGRESS;
        }

        @Override
        public void init() {
            if (initFails) {
                throw new RuntimeException("mock init failure");
            }
        }

        MockTasklet blocking() {
            isBlocking = true;
            return this;
        }

        MockTasklet sleeping() {
            isSleeping = true;
            isBlocking = true;
            return this;
        }

        MockTasklet initFails() {
            initFails = true;
            return this;
        }

        MockTasklet callFails() {
            callFails = true;
            return this;
        }

        MockTasklet callsBeforeDone(int count) {
            callsBeforeDone = count;
            return this;
        }

        void assertDone() {
            assertEquals("Tasklet wasn't done", -1, callsBeforeDone);
        }

        void assertNotDone() {
            assertNotEquals("Tasklet was done", -1, callsBeforeDone);
        }
    }
}
