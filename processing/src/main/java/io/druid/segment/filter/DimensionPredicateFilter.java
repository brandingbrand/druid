/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.segment.filter;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.metamx.collections.bitmap.ImmutableBitmap;
import com.metamx.common.guava.FunctionalIterable;
import io.druid.query.filter.BitmapIndexSelector;
import io.druid.query.filter.Filter;
import io.druid.query.filter.ValueMatcher;
import io.druid.query.filter.ValueMatcherFactory;
import io.druid.segment.ColumnSelectorFactory;
import io.druid.segment.data.Indexed;

import javax.annotation.Nullable;

/**
 */
class DimensionPredicateFilter implements Filter
{
  private final String dimension;
  private final Predicate<String> predicate;

  public DimensionPredicateFilter(
      String dimension,
      Predicate<String> predicate
  )
  {
    this.dimension = dimension;
    this.predicate = predicate;
  }

  @Override
  public ImmutableBitmap getBitmapIndex(final BitmapIndexSelector selector)
  {
    Indexed<String> dimValues = selector.getDimensionValues(dimension);
    if (dimValues == null || dimValues.size() == 0 || predicate == null) {
      return selector.getBitmapFactory().makeEmptyImmutableBitmap();
    }

    return selector.getBitmapFactory().union(
        FunctionalIterable.create(dimValues)
                          .filter(predicate)
                          .transform(
                              new Function<String, ImmutableBitmap>()
                              {
                                @Override
                                public ImmutableBitmap apply(@Nullable String input)
                                {
                                  return selector.getBitmapIndex(dimension, input);
                                }
                              }
                          )
    );
  }

  @Override
  public ValueMatcher makeMatcher(ValueMatcherFactory factory)
  {
    return factory.makeValueMatcher(dimension, predicate);
  }

  @Override
  public ValueMatcher makeMatcher(ColumnSelectorFactory factory)
  {
    throw new UnsupportedOperationException();
  }
}
