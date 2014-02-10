/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.query.groupby;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.metamx.common.ISE;
import com.metamx.common.guava.Accumulator;
import com.metamx.common.guava.ConcatSequence;
import com.metamx.common.guava.Sequence;
import com.metamx.common.guava.Sequences;
import com.metamx.emitter.service.ServiceMetricEvent;
import io.druid.data.input.MapBasedRow;
import io.druid.data.input.Row;
import io.druid.data.input.Rows;
import io.druid.granularity.QueryGranularity;
import io.druid.query.IntervalChunkingQueryRunner;
import io.druid.query.ParallelQueryRunner;
import io.druid.query.Query;
import io.druid.query.QueryRunner;
import io.druid.query.QueryToolChest;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.MetricManipulationFn;
import io.druid.query.dimension.DimensionSpec;
import io.druid.segment.incremental.IncrementalIndex;
import org.joda.time.Interval;
import org.joda.time.Minutes;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 */
public class GroupByQueryQueryToolChest extends QueryToolChest<Row, GroupByQuery>
{
  private static final TypeReference<Row> TYPE_REFERENCE = new TypeReference<Row>()
  {
  };
  private static final String GROUP_BY_MERGE_KEY = "groupByMerge";
  private static final Map<String, String> NO_MERGE_CONTEXT = ImmutableMap.of(GROUP_BY_MERGE_KEY, "false");
  private final Supplier<GroupByQueryConfig> configSupplier;

  @Inject
  public GroupByQueryQueryToolChest(
      Supplier<GroupByQueryConfig> configSupplier
  )
  {
    this.configSupplier = configSupplier;
  }

  @Override
  public QueryRunner<Row> mergeResults(final QueryRunner<Row> runner)
  {
    return new QueryRunner<Row>()
    {
      @Override
      public Sequence<Row> run(Query<Row> input)
      {
        if (Boolean.valueOf(input.getContextValue(GROUP_BY_MERGE_KEY, "true"))) {
          return mergeGroupByResults(((GroupByQuery) input).withOverriddenContext(NO_MERGE_CONTEXT), runner);
        } else {
          return runner.run(input);
        }
      }
    };
  }

  private Sequence<Row> mergeGroupByResults(final GroupByQuery query, QueryRunner<Row> runner)
  {
    final GroupByQueryConfig config = configSupplier.get();
    final QueryGranularity gran = query.getGranularity();
    final long timeStart = query.getIntervals().get(0).getStartMillis();

    // use gran.iterable instead of gran.truncate so that
    // AllGranularity returns timeStart instead of Long.MIN_VALUE
    final long granTimeStart = gran.iterable(timeStart, timeStart + 1).iterator().next();

    final List<AggregatorFactory> aggs = Lists.transform(
        query.getAggregatorSpecs(),
        new Function<AggregatorFactory, AggregatorFactory>()
        {
          @Override
          public AggregatorFactory apply(@Nullable AggregatorFactory input)
          {
            return input.getCombiningFactory();
          }
        }
    );
    final List<String> dimensions = Lists.transform(
        query.getDimensions(),
        new Function<DimensionSpec, String>()
        {
          @Override
          public String apply(@Nullable DimensionSpec input)
          {
            return input.getOutputName();
          }
        }
    );
    IncrementalIndex index = new IncrementalIndex(
        // use granularity truncated min timestamp
        // since incoming truncated timestamps may precede timeStart
        granTimeStart,
        gran,
        aggs.toArray(new AggregatorFactory[aggs.size()])
    );
    Accumulator<IncrementalIndex, Row> accumulator = new Accumulator<IncrementalIndex, Row>()
    {
      @Override
      public IncrementalIndex accumulate(IncrementalIndex accumulated, Row in)
      {
        if (accumulated.add(Rows.toCaseInsensitiveInputRow(in, dimensions)) > config.getMaxResults()) {
          throw new ISE("Computation exceeds maxRows limit[%s]", config.getMaxResults());
        }

        return accumulated;
      }
    };


    if (runner instanceof ParallelQueryRunner && Boolean.getBoolean("optimize")) {
      index = (IncrementalIndex) ((ParallelQueryRunner) runner).runAndAccumulate(query, index, accumulator);
    } else {
      index = runner.run(query).accumulate(index, accumulator);
    }

    // convert millis back to timestamp according to granularity to preserve time zone information
    Sequence<Row> retVal = Sequences.map(
        Sequences.simple(index.iterableWithPostAggregations(query.getPostAggregatorSpecs())),
        new Function<Row, Row>()
        {
          @Override
          public Row apply(Row input)
          {
            final MapBasedRow row = (MapBasedRow) input;
            return new MapBasedRow(gran.toDateTime(row.getTimestampFromEpoch()), row.getEvent());
          }
        }
    );

    return query.applyLimit(retVal);
  }

  @Override
  public Sequence<Row> mergeSequences(Sequence<Sequence<Row>> seqOfSequences)
  {
    return new ConcatSequence<Row>(seqOfSequences);
  }

  @Override
  public ServiceMetricEvent.Builder makeMetricBuilder(GroupByQuery query)
  {
    int numMinutes = 0;
    for (Interval interval : query.getIntervals()) {
      numMinutes += Minutes.minutesIn(interval).getMinutes();
    }

    return new ServiceMetricEvent.Builder()
        .setUser2(query.getDataSource())
        .setUser3(String.format("%,d dims", query.getDimensions().size()))
        .setUser4("groupBy")
        .setUser5(Joiner.on(",").join(query.getIntervals()))
        .setUser6(String.valueOf(query.hasFilters()))
        .setUser7(String.format("%,d aggs", query.getAggregatorSpecs().size()))
        .setUser9(Minutes.minutes(numMinutes).toString());
  }

  @Override
  public Function<Row, Row> makeMetricManipulatorFn(final GroupByQuery query, final MetricManipulationFn fn)
  {
    return new Function<Row, Row>()
    {
      @Override
      public Row apply(Row input)
      {
        if (input instanceof MapBasedRow) {
          final MapBasedRow inputRow = (MapBasedRow) input;
          final Map<String, Object> values = Maps.newHashMap(((MapBasedRow) input).getEvent());
          for (AggregatorFactory agg : query.getAggregatorSpecs()) {
            values.put(agg.getName(), fn.manipulate(agg, inputRow.getEvent().get(agg.getName())));
          }
          return new MapBasedRow(inputRow.getTimestamp(), values);
        }
        return input;
      }
    };
  }

  @Override
  public TypeReference<Row> getResultTypeReference()
  {
    return TYPE_REFERENCE;
  }

  @Override
  public QueryRunner<Row> preMergeQueryDecoration(QueryRunner<Row> runner)
  {
    return new IntervalChunkingQueryRunner<Row>(runner, configSupplier.get().getChunkPeriod());
  }
}
