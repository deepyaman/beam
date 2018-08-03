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
package org.apache.beam.sdk.io.synthetic;

import static org.apache.beam.sdk.io.synthetic.SideInputLoadIT.SideInputType.ITERABLE;
import static org.apache.beam.sdk.io.synthetic.SideInputLoadIT.SideInputType.MAP;
import static org.apache.beam.sdk.io.synthetic.SyntheticSourceTestUtils.fromString;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Map;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.PipelineOptionsValidator;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestPipelineOptions;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.Top;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Performance fanout test for {@link CoGroupByKey}. */
@RunWith(JUnit4.class)
public class SideInputLoadIT {

  private static Options options;

  private static SyntheticBoundedIO.SyntheticSourceOptions inputOptions;

  private static SyntheticBoundedIO.SyntheticSourceOptions sideInputOptions;

  @Rule public TestPipeline pipeline = TestPipeline.create();

  public enum SideInputType {
    ITERABLE,
    MAP
  }

  /** Pipeline options for the test. */
  public interface Options extends TestPipelineOptions {

    @Description("The JSON representation of SyntheticBoundedInput.SourceOptions for main input.")
    @Validation.Required
    String getInputOptions();

    void setInputOptions(String inputOptions);

    @Description("The JSON representation of SyntheticBoundedInput.SourceOptions for side input.")
    @Validation.Required
    String getSideInputOptions();

    void setSideInputOptions(String sideInputOptions);

    @Description("The number of gbk to perform")
    @Default.Integer(5)
    Integer getFanout();

    void setFanout(Integer shuffleFanout);

    @Description("Type of side input")
    @Default.Enum("ITERABLE")
    SideInputType getSideInputType();

    void setSideInputType(SideInputType sideInputType);

    @Description("The number of reiterations to perform")
    @Default.Integer(1)
    Integer getIterations();

    void setIterations(Integer iterations);
  }

  @BeforeClass
  public static void setup() throws IOException {
    PipelineOptionsFactory.register(Options.class);

    options =
        PipelineOptionsValidator.validate(
            Options.class, TestPipeline.testingPipelineOptions().as(Options.class));

    inputOptions = fromString(options.getInputOptions());
    sideInputOptions = fromString(options.getSideInputOptions());
  }

  @Test
  public void sideInputLoadTest() {
    PCollection<KV<byte[], byte[]>> input =
        pipeline.apply(SyntheticBoundedIO.readFrom(inputOptions));

    PCollectionView<Iterable<KV<byte[], byte[]>>> sideInput = pipeline
            .apply(SyntheticBoundedIO.readFrom(inputOptions))
            .apply(View.asIterable());

    input
        .apply(ParDo.of(new SyntheticStep(stepOptions)))
        .apply(ParDo.of(
                    new DoFn<KV<byte[], byte[]>, KV<byte[], byte[]>>() {
                      @ProcessElement
                      public void processElement(ProcessContext c) {
                        Iterable<KV<byte[], byte[]>> si = c.sideInput(sideInput);

                        // Retiterate
                        for (int i = 0; i < options.getIterations(); i++) {
                          for (KV<byte[], byte[]> sideInputElement : si) {
                            // for every _input_ element iterate over all _sideInput_ elements
                            // count consumed bytes, examine memory usage, etc (Metrics API).
                          }
                        }
                      }
                    })
                .withSideInputs(sideInput));
    pipeline.run().waitUntilFinish();
  }

  /*
   * TODO:
   *
   * Should we use metrics API, as in the Side input gist?
   * (https://gist.github.com/pabloem/eeb97d25ebda43db09ff9b875f61f127)
   *
   * Do I think correctly, that we should also use the metrics api in all other tests to
   * gather more data about the test runs (or is it out of scope, at least for now)?
   *
   */

  /* For every element, iterate over the whole iterable side input. */

  /* For every element, find its corresponding value in side input. */
  private static class ConsumeMap extends DoFn<KV<byte[], byte[]>, KV<byte[], byte[]>> {

    private PCollectionView<Map<byte[], Iterable<byte[]>>> sideInput;

    ConsumeMap(PCollectionView<Map<byte[], Iterable<byte[]>>> sideInput) {
      this.sideInput = sideInput;
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
      Map<byte[], Iterable<byte[]>> map = c.sideInput(this.sideInput);

      Iterable<byte[]> bytes = map.get(c.element().getKey());

      //      if(bytes == null) {
      //        // TODO: missing key
      //      } else {
      //        // TODO: found key
      //      }
    }
  }
}
