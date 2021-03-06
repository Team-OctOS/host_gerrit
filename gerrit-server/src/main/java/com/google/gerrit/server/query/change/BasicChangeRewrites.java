// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.query.change;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.query.IntPredicate;
import com.google.gerrit.server.query.Predicate;
import com.google.gerrit.server.query.QueryRewriter;
import com.google.inject.Inject;
import com.google.inject.OutOfScopeException;
import com.google.inject.Provider;
import com.google.inject.name.Named;

public class BasicChangeRewrites extends QueryRewriter<ChangeData> {
  private static final ChangeQueryBuilder BUILDER = new ChangeQueryBuilder(
      new ChangeQueryBuilder.Arguments( //
          new InvalidProvider<ReviewDb>(), //
          new InvalidProvider<ChangeQueryRewriter>(), //
          null, null, null, null, null, null, null, //
          null, null, null, null, null, null, null, null, null, null), null);

  private static final QueryRewriter.Definition<ChangeData, BasicChangeRewrites> mydef =
      new QueryRewriter.Definition<ChangeData, BasicChangeRewrites>(
          BasicChangeRewrites.class, BUILDER);

  protected final Provider<ReviewDb> dbProvider;

  @Inject
  public BasicChangeRewrites(Provider<ReviewDb> dbProvider) {
    super(mydef);
    this.dbProvider = dbProvider;
  }

  @Rewrite("-status:open")
  @NoCostComputation
  public Predicate<ChangeData> r00_notOpen() {
    return ChangeStatusPredicate.closed(dbProvider);
  }

  @Rewrite("-status:closed")
  @NoCostComputation
  public Predicate<ChangeData> r00_notClosed() {
    return ChangeStatusPredicate.open(dbProvider);
  }

  @SuppressWarnings("unchecked")
  @NoCostComputation
  @Rewrite("-status:merged")
  public Predicate<ChangeData> r00_notMerged() {
    return or(ChangeStatusPredicate.open(dbProvider),
        new ChangeStatusPredicate(Change.Status.ABANDONED));
  }

  @SuppressWarnings("unchecked")
  @NoCostComputation
  @Rewrite("-status:abandoned")
  public Predicate<ChangeData> r00_notAbandoned() {
    return or(ChangeStatusPredicate.open(dbProvider),
        new ChangeStatusPredicate(Change.Status.MERGED));
  }

  @NoCostComputation
  @Rewrite("A=(limit:*) B=(limit:*)")
  public Predicate<ChangeData> r00_smallestLimit(
      @Named("A") IntPredicate<ChangeData> a,
      @Named("B") IntPredicate<ChangeData> b) {
    return a.intValue() <= b.intValue() ? a : b;
  }

  private static final class InvalidProvider<T> implements Provider<T> {
    @Override
    public T get() {
      throw new OutOfScopeException("Not available at init");
    }
  }
}
