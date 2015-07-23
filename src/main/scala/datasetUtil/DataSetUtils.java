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


package datasetUtil;


import org.apache.flink.api.common.functions.RichMapPartitionFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This class provides simple utility methods for zipping elements in a data set with an index
 * or with a unique identifier.
 */
public class DataSetUtils {

    /**
     * Method that goes over all the elements in each partition in order to retrieve
     * the total number of elements.
     *
     * @param input the DataSet received as input
     * @return a data set containing tuples of subtask index, number of elements mappings.
     */
    private static <T> DataSet<Tuple2<Integer, Long>> countElements(DataSet<T> input) {
        return input.mapPartition(new RichMapPartitionFunction<T, Tuple2<Integer, Long>>() {
            @Override
            public void mapPartition(Iterable<T> values, Collector<Tuple2<Integer, Long>> out) throws Exception {
                long counter = 0;
                for (T value : values) {
                    counter++;
                }

                out.collect(new Tuple2<Integer, Long>(getRuntimeContext().getIndexOfThisSubtask(), counter));
            }
        });
    }

    /**
     * Method that takes a set of subtask index, total number of elements mappings
     * and assigns ids to all the elements from the input data set.
     *
     * @param input the input data set
     * @return a data set of tuple 2 consisting of consecutive ids and initial values.
     */
    public static <T> DataSet<Tuple2<Long, T>> zipWithIndex(DataSet<T> input) {

        DataSet<Tuple2<Integer, Long>> elementCount = countElements(input);

        return input.mapPartition(new RichMapPartitionFunction<T, Tuple2<Long, T>>() {

            long start = 0;

            // compute the offset for each partition
            @Override
            public void open(Configuration parameters) throws Exception {
                super.open(parameters);

                List<Tuple2<Integer, Long>> offsets = new CopyOnWriteArrayList<Tuple2<Integer, Long>>(getRuntimeContext().<Tuple2<Integer, Long>>getBroadcastVariable("counts"));

                Collections.sort(offsets, new Comparator<Tuple2<Integer, Long>>() {
                    @Override
                    public int compare(Tuple2<Integer, Long> o1, Tuple2<Integer, Long> o2) {
                        return compareInts(o1.f0, o2.f0);
                    }
                });

                for (int i = 0; i < getRuntimeContext().getIndexOfThisSubtask(); i++) {
                    start += offsets.get(i).f1;
                }
            }

            @Override
            public void mapPartition(Iterable<T> values, Collector<Tuple2<Long, T>> out) throws Exception {
                for (T value : values) {
                    out.collect(new Tuple2<Long, T>(start++, value));
                }
            }
        }).withBroadcastSet(elementCount, "counts");
    }

    /**
     * Method that assigns unique Long labels to all the elements in the input data set by making use of the
     * following abstractions:
     * <ul>
     * <li> a map function generates an n-bit (n - number of parallel tasks) ID based on its own index
     * <li> with each record, a counter c is increased
     * <li> the unique label is then produced by shifting the counter c by the n-bit mapper ID
     * </ul>
     *
     * @param input the input data set
     * @return a data set of tuple 2 consisting of ids and initial values.
     */
    public static <T> DataSet<Tuple2<Long, T>> zipWithUniqueId(DataSet<T> input) {

        return input.mapPartition(new RichMapPartitionFunction<T, Tuple2<Long, T>>() {

            long shifter = 0;
            long start = 0;
            long taskId = 0;
            long label = 0;

            @Override
            public void open(Configuration parameters) throws Exception {
                super.open(parameters);
                shifter = log2(getRuntimeContext().getNumberOfParallelSubtasks());
                taskId = getRuntimeContext().getIndexOfThisSubtask();
            }

            @Override
            public void mapPartition(Iterable<T> values, Collector<Tuple2<Long, T>> out) throws Exception {
                for (T value : values) {
                    label = start << shifter + taskId;

                    if (log2(start) + shifter < log2(Long.MAX_VALUE)) {
                        out.collect(new Tuple2<Long, T>(label, value));
                        start++;
                    } else {
                        throw new Exception("Exceeded Long value range while generating labels");
                    }
                }
            }
        });
    }

    // *************************************************************************
    //     UTIL METHODS
    // *************************************************************************

    private static int compareInts(int x, int y) {
        return (x < y) ? -1 : ((x == y) ? 0 : 1);
    }

    private static int log2(long value) {
        if (value > Integer.MAX_VALUE) {
            return 64 - Integer.numberOfLeadingZeros((int) (value >> 32));
        } else {
            return 32 - Integer.numberOfLeadingZeros((int) value);
        }
    }
}