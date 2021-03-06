/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.Sink.WriteOperation;
import org.apache.beam.sdk.io.Sink.Writer;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.windowing.DefaultTrigger;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollection.IsBounded;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PDone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link PTransform} that writes to a {@link Sink}. A write begins with a sequential global
 * initialization of a sink, followed by a parallel write, and ends with a sequential finalization
 * of the write. The output of a write is {@link PDone}.
 *
 * <p>By default, every bundle in the input {@link PCollection} will be processed by a
 * {@link WriteOperation}, so the number of outputs will vary based on runner behavior, though at
 * least 1 output will always be produced. The exact parallelism of the write stage can be
 * controlled using {@link Write#withNumShards}, typically used to control how many files are
 * produced or to globally limit the number of workers connecting to an external service. However,
 * this option can often hurt performance: it adds an additional {@link GroupByKey} to the pipeline.
 *
 * <p>{@code Write} re-windows the data into the global window, so it is typically not well suited
 * to use in streaming pipelines.
 *
 * <p>Example usage with runner-determined sharding:
 *
 * <pre>{@code p.apply(Write.to(new MySink(...)));}</pre>
 *
 * <p>Example usage with a fixed number of shards:
 *
 * <pre>{@code p.apply(Write.to(new MySink(...)).withNumShards(3));}</pre>
 */
@Experimental(Experimental.Kind.SOURCE_SINK)
public class Write<T> extends PTransform<PCollection<T>, PDone> {
  private static final Logger LOG = LoggerFactory.getLogger(Write.class);

  private final Sink<T> sink;
  @Nullable
  private final PTransform<PCollection<T>, PCollectionView<Integer>> computeNumShards;

  /**
   * Creates a {@link Write} transform that writes to the given {@link Sink}, letting the runner
   * control how many different shards are produced.
   */
  public static <T> Write<T> to(Sink<T> sink) {
    checkNotNull(sink, "sink");
    return new Write<>(sink, null /* runner-determined sharding */);
  }

  private Write(
      Sink<T> sink,
      @Nullable PTransform<PCollection<T>, PCollectionView<Integer>> computeNumShards) {
    this.sink = sink;
    this.computeNumShards = computeNumShards;
  }

  @Override
  public PDone expand(PCollection<T> input) {
    checkArgument(
        IsBounded.BOUNDED == input.isBounded(),
        "%s can only be applied to a Bounded PCollection",
        Write.class.getSimpleName());
    PipelineOptions options = input.getPipeline().getOptions();
    sink.validate(options);
    return createWrite(input, sink.createWriteOperation(options));
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);
    builder
        .add(DisplayData.item("sink", sink.getClass()).withLabel("Write Sink"))
        .include("sink", sink);
    if (getSharding() != null) {
      builder.include("sharding", getSharding());
    }
  }

  /**
   * Returns the {@link Sink} associated with this PTransform.
   */
  public Sink<T> getSink() {
    return sink;
  }

  /**
   * Gets the {@link PTransform} that will be used to determine sharding. This can be either a
   * static number of shards (as following a call to {@link #withNumShards(int)}), dynamic (by
   * {@link #withSharding(PTransform)}), or runner-determined (by {@link
   * #withRunnerDeterminedSharding()}.
   */
  @Nullable
  public PTransform<PCollection<T>, PCollectionView<Integer>> getSharding() {
    return computeNumShards;
  }

  /**
   * Returns a new {@link Write} that will write to the current {@link Sink} using the
   * specified number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link Write} for
   * more information.
   *
   * <p>A value less than or equal to 0 will be equivalent to the default behavior of
   * runner-determined sharding.
   */
  public Write<T> withNumShards(int numShards) {
    if (numShards > 0) {
      return withNumShards(StaticValueProvider.of(numShards));
    }
    return withRunnerDeterminedSharding();
  }

  /**
   * Returns a new {@link Write} that will write to the current {@link Sink} using the
   * {@link ValueProvider} specified number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link Write} for
   * more information.
   */
  public Write<T> withNumShards(ValueProvider<Integer> numShards) {
    return new Write<>(sink, new ConstantShards<T>(numShards));
  }

  /**
   * Returns a new {@link Write} that will write to the current {@link Sink} using the
   * specified {@link PTransform} to compute the number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link Write} for
   * more information.
   */
  public Write<T> withSharding(PTransform<PCollection<T>, PCollectionView<Integer>> sharding) {
    checkNotNull(
        sharding, "Cannot provide null sharding. Use withRunnerDeterminedSharding() instead");
    return new Write<>(sink, sharding);
  }

  /**
   * Returns a new {@link Write} that will write to the current {@link Sink} with
   * runner-determined sharding.
   */
  public Write<T> withRunnerDeterminedSharding() {
    return new Write<>(sink, null);
  }

  /**
   * Writes all the elements in a bundle using a {@link Writer} produced by the
   * {@link WriteOperation} associated with the {@link Sink}.
   */
  private class WriteBundles<WriteT> extends DoFn<T, WriteT> {
    // Writer that will write the records in this bundle. Lazily
    // initialized in processElement.
    private Writer<T, WriteT> writer = null;
    private final PCollectionView<WriteOperation<T, WriteT>> writeOperationView;

    WriteBundles(PCollectionView<WriteOperation<T, WriteT>> writeOperationView) {
      this.writeOperationView = writeOperationView;
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      // Lazily initialize the Writer
      if (writer == null) {
        WriteOperation<T, WriteT> writeOperation = c.sideInput(writeOperationView);
        LOG.info("Opening writer for write operation {}", writeOperation);
        writer = writeOperation.createWriter(c.getPipelineOptions());
        writer.open(UUID.randomUUID().toString());
        LOG.debug("Done opening writer {} for operation {}", writer, writeOperationView);
      }
      try {
        writer.write(c.element());
      } catch (Exception e) {
        // Discard write result and close the write.
        try {
          writer.close();
          // The writer does not need to be reset, as this DoFn cannot be reused.
        } catch (Exception closeException) {
          if (closeException instanceof InterruptedException) {
            // Do not silently ignore interrupted state.
            Thread.currentThread().interrupt();
          }
          // Do not mask the exception that caused the write to fail.
          e.addSuppressed(closeException);
        }
        throw e;
      }
    }

    @FinishBundle
    public void finishBundle(Context c) throws Exception {
      if (writer != null) {
        WriteT result = writer.close();
        c.output(result);
        // Reset state in case of reuse.
        writer = null;
      }
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.delegate(Write.this);
    }
  }

  /**
   * Like {@link WriteBundles}, but where the elements for each shard have been collected into
   * a single iterable.
   *
   * @see WriteBundles
   */
  private class WriteShardedBundles<WriteT> extends DoFn<KV<Integer, Iterable<T>>, WriteT> {
    private final PCollectionView<WriteOperation<T, WriteT>> writeOperationView;

    WriteShardedBundles(PCollectionView<WriteOperation<T, WriteT>> writeOperationView) {
      this.writeOperationView = writeOperationView;
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      // In a sharded write, single input element represents one shard. We can open and close
      // the writer in each call to processElement.
      WriteOperation<T, WriteT> writeOperation = c.sideInput(writeOperationView);
      LOG.info("Opening writer for write operation {}", writeOperation);
      Writer<T, WriteT> writer = writeOperation.createWriter(c.getPipelineOptions());
      writer.open(UUID.randomUUID().toString());
      LOG.debug("Done opening writer {} for operation {}", writer, writeOperationView);

      try {
        for (T t : c.element().getValue()) {
          writer.write(t);
        }
      } catch (Exception e) {
        try {
          writer.close();
        } catch (Exception closeException) {
          if (closeException instanceof InterruptedException) {
            // Do not silently ignore interrupted state.
            Thread.currentThread().interrupt();
          }
          // Do not mask the exception that caused the write to fail.
          e.addSuppressed(closeException);
        }
        throw e;
      }

      // Close the writer; if this throws let the error propagate.
      WriteT result = writer.close();
      c.output(result);
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.delegate(Write.this);
    }
  }

  private static class ApplyShardingKey<T> extends DoFn<T, KV<Integer, T>> {
    private final PCollectionView<Integer> numShards;
    private int shardNumber;

    ApplyShardingKey(PCollectionView<Integer> numShards) {
      this.numShards = numShards;
      shardNumber = -1;
    }

    @ProcessElement
    public void processElement(ProcessContext context) {
      Integer shardCount = context.sideInput(numShards);
      checkArgument(
          shardCount > 0,
          "Must have a positive number of shards specified for non-runner-determined sharding."
              + " Got %s",
          shardCount);
      if (shardNumber == -1) {
        // We want to desynchronize the first record sharding key for each instance of
        // ApplyShardingKey, so records in a small PCollection will be statistically balanced.
        shardNumber = ThreadLocalRandom.current().nextInt(shardCount);
      } else {
        shardNumber = (shardNumber + 1) % shardCount;
      }
      context.output(KV.of(shardNumber, context.element()));
    }
  }

  /**
   * A write is performed as sequence of three {@link ParDo}'s.
   *
   * <p>In the first, a do-once ParDo is applied to a singleton PCollection containing the Sink's
   * {@link WriteOperation}. In this initialization ParDo, {@link WriteOperation#initialize} is
   * called. The output of this ParDo is a singleton PCollection
   * containing the WriteOperation.
   *
   * <p>This singleton collection containing the WriteOperation is then used as a side input to a
   * ParDo over the PCollection of elements to write. In this bundle-writing phase,
   * {@link WriteOperation#createWriter} is called to obtain a {@link Writer}.
   * {@link Writer#open} and {@link Writer#close} are called in {@link DoFn.StartBundle} and
   * {@link DoFn.FinishBundle}, respectively, and {@link Writer#write} method is called for
   * every element in the bundle. The output of this ParDo is a PCollection of
   * <i>writer result</i> objects (see {@link Sink} for a description of writer results)-one for
   * each bundle.
   *
   * <p>The final do-once ParDo uses the singleton collection of the WriteOperation as input and
   * the collection of writer results as a side-input. In this ParDo,
   * {@link WriteOperation#finalize} is called to finalize the write.
   *
   * <p>If the write of any element in the PCollection fails, {@link Writer#close} will be called
   * before the exception that caused the write to fail is propagated and the write result will be
   * discarded.
   *
   * <p>Since the {@link WriteOperation} is serialized after the initialization ParDo and
   * deserialized in the bundle-writing and finalization phases, any state change to the
   * WriteOperation object that occurs during initialization is visible in the latter phases.
   * However, the WriteOperation is not serialized after the bundle-writing phase.  This is why
   * implementations should guarantee that {@link WriteOperation#createWriter} does not mutate
   * WriteOperation).
   */
  private <WriteT> PDone createWrite(
      PCollection<T> input, WriteOperation<T, WriteT> writeOperation) {
    Pipeline p = input.getPipeline();

    // A coder to use for the WriteOperation.
    @SuppressWarnings("unchecked")
    Coder<WriteOperation<T, WriteT>> operationCoder =
        (Coder<WriteOperation<T, WriteT>>) SerializableCoder.of(writeOperation.getClass());

    // A singleton collection of the WriteOperation, to be used as input to a ParDo to initialize
    // the sink.
    PCollection<WriteOperation<T, WriteT>> operationCollection =
        p.apply(Create.of(writeOperation).withCoder(operationCoder));

    // Initialize the resource in a do-once ParDo on the WriteOperation.
    operationCollection = operationCollection
        .apply("Initialize", ParDo.of(
            new DoFn<WriteOperation<T, WriteT>, WriteOperation<T, WriteT>>() {
          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            WriteOperation<T, WriteT> writeOperation = c.element();
            LOG.info("Initializing write operation {}", writeOperation);
            writeOperation.initialize(c.getPipelineOptions());
            LOG.debug("Done initializing write operation {}", writeOperation);
            // The WriteOperation is also the output of this ParDo, so it can have mutable
            // state.
            c.output(writeOperation);
          }
        }))
        .setCoder(operationCoder);

    // Create a view of the WriteOperation to be used as a sideInput to the parallel write phase.
    final PCollectionView<WriteOperation<T, WriteT>> writeOperationView =
        operationCollection.apply(View.<WriteOperation<T, WriteT>>asSingleton());

    // Re-window the data into the global window and remove any existing triggers.
    PCollection<T> inputInGlobalWindow =
        input.apply(
            Window.<T>into(new GlobalWindows())
                .triggering(DefaultTrigger.of())
                .discardingFiredPanes());

    // Perform the per-bundle writes as a ParDo on the input PCollection (with the WriteOperation
    // as a side input) and collect the results of the writes in a PCollection.
    // There is a dependency between this ParDo and the first (the WriteOperation PCollection
    // as a side input), so this will happen after the initial ParDo.
    PCollection<WriteT> results;
    final PCollectionView<Integer> numShards;
    if (computeNumShards == null) {
      numShards = null;
      results =
          inputInGlobalWindow.apply(
              "WriteBundles",
              ParDo.of(new WriteBundles<>(writeOperationView))
                  .withSideInputs(writeOperationView));
    } else {
      numShards = inputInGlobalWindow.apply(computeNumShards);
      results =
          inputInGlobalWindow
              .apply(
                  "ApplyShardLabel",
                  ParDo.of(new ApplyShardingKey<T>(numShards)).withSideInputs(numShards))
              .apply("GroupIntoShards", GroupByKey.<Integer, T>create())
              .apply(
                  "WriteShardedBundles",
                  ParDo.of(new WriteShardedBundles<>(writeOperationView))
                      .withSideInputs(writeOperationView));
    }
    results.setCoder(writeOperation.getWriterResultCoder());

    final PCollectionView<Iterable<WriteT>> resultsView =
        results.apply(View.<WriteT>asIterable());

    // Finalize the write in another do-once ParDo on the singleton collection containing the
    // Writer. The results from the per-bundle writes are given as an Iterable side input.
    // The WriteOperation's state is the same as after its initialization in the first do-once
    // ParDo. There is a dependency between this ParDo and the parallel write (the writer results
    // collection as a side input), so it will happen after the parallel write.
    ImmutableList.Builder<PCollectionView<?>> sideInputs =
        ImmutableList.<PCollectionView<?>>builder().add(resultsView);
    if (numShards != null) {
      sideInputs.add(numShards);
    }
    operationCollection
        .apply("Finalize", ParDo.of(new DoFn<WriteOperation<T, WriteT>, Integer>() {
          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            WriteOperation<T, WriteT> writeOperation = c.element();
            LOG.info("Finalizing write operation {}.", writeOperation);
            List<WriteT> results = Lists.newArrayList(c.sideInput(resultsView));
            LOG.debug("Side input initialized to finalize write operation {}.", writeOperation);

            // We must always output at least 1 shard, and honor user-specified numShards if set.
            int minShardsNeeded;
            if (numShards == null) {
              minShardsNeeded = 1;
            } else {
              minShardsNeeded = c.sideInput(numShards);
              checkArgument(
                  minShardsNeeded > 0,
                  "Must have a positive number of shards for non-runner-determined sharding."
                      + " Got %s",
                  minShardsNeeded);
            }
            int extraShardsNeeded = minShardsNeeded - results.size();
            if (extraShardsNeeded > 0) {
              LOG.info(
                  "Creating {} empty output shards in addition to {} written for a total of {}.",
                  extraShardsNeeded, results.size(), minShardsNeeded);
              for (int i = 0; i < extraShardsNeeded; ++i) {
                Writer<T, WriteT> writer = writeOperation.createWriter(c.getPipelineOptions());
                writer.open(UUID.randomUUID().toString());
                WriteT emptyWrite = writer.close();
                results.add(emptyWrite);
              }
              LOG.debug("Done creating extra shards.");
            }

            writeOperation.finalize(results, c.getPipelineOptions());
            LOG.debug("Done finalizing write operation {}", writeOperation);
          }
        }).withSideInputs(sideInputs.build()));
    return PDone.in(input.getPipeline());
  }

  @VisibleForTesting
  static class ConstantShards<T>
      extends PTransform<PCollection<T>, PCollectionView<Integer>> {
    private final ValueProvider<Integer> numShards;

    private ConstantShards(ValueProvider<Integer> numShards) {
      this.numShards = numShards;
    }

    @Override
    public PCollectionView<Integer> expand(PCollection<T> input) {
      return input
          .getPipeline()
          .apply(Create.of(0))
          .apply(
              "FixedNumShards",
              ParDo.of(
                  new DoFn<Integer, Integer>() {
                    @ProcessElement
                    public void outputNumShards(ProcessContext ctxt) {
                      checkArgument(
                          numShards.isAccessible(),
                          "NumShards must be accessible at runtime to use constant sharding");
                      ctxt.output(numShards.get());
                    }
                  }))
          .apply(View.<Integer>asSingleton());
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.add(
          DisplayData.item("Fixed Number of Shards", numShards).withLabel("ConstantShards"));
    }

    public ValueProvider<Integer> getNumShards() {
      return numShards;
    }
  }
}
