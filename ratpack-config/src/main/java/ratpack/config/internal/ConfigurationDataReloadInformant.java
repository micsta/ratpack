/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.config.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.exec.ExecControl;
import ratpack.exec.Execution;
import ratpack.func.Action;
import ratpack.server.ReloadInformant;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConfigurationDataReloadInformant implements ReloadInformant {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationDataReloadInformant.class);

  private final ObjectNode currentNode;
  private final AtomicBoolean changeDetected = new AtomicBoolean();
  private final ConfigurationDataLoader loader;
  private final Lock lock = new ReentrantLock();
  private Duration interval = Duration.ofSeconds(60);
  private ScheduledFuture<?> future;

  public ConfigurationDataReloadInformant(ObjectNode currentNode, ConfigurationDataLoader loader) {
    this.currentNode = currentNode;
    this.loader = loader;
  }

  public ConfigurationDataReloadInformant interval(Duration interval) {
    this.interval = interval;
    return this;
  }

  @Override
  public boolean shouldReload() {
    schedulePollIfNotRunning();
    return changeDetected.get();
  }

  private void schedulePollIfNotRunning() {
    if (!isPollRunning()) {
      lock.lock();
      try {
        if (!isPollRunning()) {
          future = schedulePoll();
        }
      } finally {
        lock.unlock();
      }
    }
  }

  private boolean isPollRunning() {
    return future != null && !future.isDone();
  }

  private ScheduledFuture<?> schedulePoll() {
    LOGGER.debug("Scheduling configuration poll in {}", interval);
    ExecControl execControl = ExecControl.current();
    ScheduledExecutorService scheduledExecutorService = execControl.getController().getExecutor();
    return scheduledExecutorService.schedule(execPoll(execControl), interval.getSeconds(), TimeUnit.SECONDS);
  }

  private Runnable execPoll(ExecControl execControl) {
    return () -> execControl.exec().start(poll());
  }

  private Action<Execution> poll() {
    return execution -> {
      execution.getControl()
        .blocking(loader::load)
        .onError(Action.noop())
        .then(processData());
    };
  }

  private Action<ObjectNode> processData() {
    return newNode -> {
      if (currentNode.equals(newNode)) {
        LOGGER.debug("No difference in configuration data");
        lock.lock();
        try {
          future = schedulePoll();
        } finally {
          lock.unlock();
        }
      } else {
        LOGGER.info("Configuration data difference detected; next request should reload");
        changeDetected.set(true);
      }
    };
  }

  @Override
  public String toString() {
    return "configuration data reload informant";
  }
}