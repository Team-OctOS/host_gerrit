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

package com.google.gerrit.server.change;

import static com.google.inject.Scopes.SINGLETON;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.gerrit.common.changes.Side;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.CommentRange;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.reviewdb.client.PatchLineComment;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.PatchLineComment.Status;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.PatchLineCommentAccess;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.PatchLineCommentsUtil;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountInfo;
import com.google.gerrit.server.account.CapabilityControl;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.config.AnonymousCowardName;
import com.google.gerrit.server.config.AnonymousCowardNameProvider;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.GitModule;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.notedb.ChangeUpdate;
import com.google.gerrit.server.notedb.NotesMigration;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.util.TimeUtil;
import com.google.gerrit.testutil.TestChanges;
import com.google.gerrit.testutil.ConfigSuite;
import com.google.gerrit.testutil.FakeAccountCache;
import com.google.gerrit.testutil.InMemoryRepositoryManager;
import com.google.gwtorm.server.ListResultSet;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.ResultSet;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Providers;

import org.easymock.IAnswer;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

@RunWith(ConfigSuite.class)
public class CommentsTest  {
  private static final TimeZone TZ =
      TimeZone.getTimeZone("America/Los_Angeles");

  @ConfigSuite.Parameter
  public Config config;

  @ConfigSuite.Config
  public static @GerritServerConfig Config noteDbEnabled() {
    @GerritServerConfig Config cfg = new Config();
    cfg.setBoolean("notedb", null, "write", true);
    cfg.setBoolean("notedb", "publishedComments", "read", true);
    return cfg;
  }

  private Injector injector;
  private Project.NameKey project;
  private InMemoryRepositoryManager repoManager;
  private PatchLineCommentsUtil plcUtil;
  private RevisionResource revRes1;
  private RevisionResource revRes2;
  private PatchLineComment plc1;
  private PatchLineComment plc2;
  private PatchLineComment plc3;
  private IdentifiedUser changeOwner;

  @Before
  public void setUp() throws Exception {
    @SuppressWarnings("unchecked")
    final DynamicMap<RestView<CommentResource>> views =
        createMock(DynamicMap.class);
    final TypeLiteral<DynamicMap<RestView<CommentResource>>> viewsType =
        new TypeLiteral<DynamicMap<RestView<CommentResource>>>() {};
    final AccountInfo.Loader.Factory alf =
        createMock(AccountInfo.Loader.Factory.class);
    final ReviewDb db = createMock(ReviewDb.class);
    final FakeAccountCache accountCache = new FakeAccountCache();
    final PersonIdent serverIdent = new PersonIdent(
        "Gerrit Server", "noreply@gerrit.com", TimeUtil.nowTs(), TZ);
    project = new Project.NameKey("test-project");
    repoManager = new InMemoryRepositoryManager();

    @SuppressWarnings("unused")
    InMemoryRepository repo = repoManager.createRepository(project);

    AbstractModule mod = new AbstractModule() {
      @Override
      protected void configure() {
        bind(viewsType).toInstance(views);
        bind(AccountInfo.Loader.Factory.class).toInstance(alf);
        bind(ReviewDb.class).toProvider(Providers.<ReviewDb>of(db));
        bind(Config.class).annotatedWith(GerritServerConfig.class).toInstance(config);
        bind(ProjectCache.class).toProvider(Providers.<ProjectCache> of(null));
        install(new GitModule());
        bind(GitRepositoryManager.class).toInstance(repoManager);
        bind(CapabilityControl.Factory.class)
            .toProvider(Providers.<CapabilityControl.Factory> of(null));
        bind(String.class).annotatedWith(AnonymousCowardName.class)
            .toProvider(AnonymousCowardNameProvider.class);
        bind(String.class).annotatedWith(CanonicalWebUrl.class)
            .toInstance("http://localhost:8080/");
        bind(GroupBackend.class).to(SystemGroupBackend.class).in(SINGLETON);
        bind(AccountCache.class).toInstance(accountCache);
        bind(GitReferenceUpdated.class)
            .toInstance(GitReferenceUpdated.DISABLED);
        bind(PersonIdent.class).annotatedWith(GerritPersonIdent.class)
          .toInstance(serverIdent);
      }
    };

    injector = Guice.createInjector(mod);

    NotesMigration migration = injector.getInstance(NotesMigration.class);
    plcUtil = new PatchLineCommentsUtil(migration);

    Account co = new Account(new Account.Id(1), TimeUtil.nowTs());
    co.setFullName("Change Owner");
    co.setPreferredEmail("change@owner.com");
    accountCache.put(co);
    Account.Id ownerId = co.getId();

    Account ou = new Account(new Account.Id(2), TimeUtil.nowTs());
    ou.setFullName("Other Account");
    ou.setPreferredEmail("other@account.com");
    accountCache.put(ou);
    Account.Id otherUserId = ou.getId();

    IdentifiedUser.GenericFactory userFactory =
        injector.getInstance(IdentifiedUser.GenericFactory.class);
    changeOwner = userFactory.create(ownerId);
    IdentifiedUser otherUser = userFactory.create(otherUserId);

    AccountInfo.Loader accountLoader = createMock(AccountInfo.Loader.class);
    accountLoader.fill();
    expectLastCall().anyTimes();
    expect(accountLoader.get(ownerId))
        .andReturn(new AccountInfo(ownerId)).anyTimes();
    expect(accountLoader.get(otherUserId))
        .andReturn(new AccountInfo(otherUserId)).anyTimes();
    expect(alf.create(true)).andReturn(accountLoader).anyTimes();
    replay(accountLoader, alf);

    PatchLineCommentAccess plca = createMock(PatchLineCommentAccess.class);
    expect(db.patchComments()).andReturn(plca).anyTimes();

    Change change = newChange();
    PatchSet.Id psId1 = new PatchSet.Id(change.getId(), 1);
    PatchSet ps1 = new PatchSet(psId1);
    PatchSet.Id psId2 = new PatchSet.Id(change.getId(), 2);
    PatchSet ps2 = new PatchSet(psId2);

    long timeBase = TimeUtil.nowMs();
    plc1 = newPatchLineComment(psId1, "Comment1", null,
        "FileOne.txt", Side.REVISION, 3, ownerId, timeBase,
        "First Comment", new CommentRange(1, 2, 3, 4));
    plc1.setRevId(new RevId("ABCDABCDABCDABCDABCDABCDABCDABCDABCDABCD"));
    plc2 = newPatchLineComment(psId1, "Comment2", "Comment1",
        "FileOne.txt", Side.REVISION, 3, otherUserId, timeBase + 1000,
        "Reply to First Comment",  new CommentRange(1, 2, 3, 4));
    plc2.setRevId(new RevId("ABCDABCDABCDABCDABCDABCDABCDABCDABCDABCD"));
    plc3 = newPatchLineComment(psId1, "Comment3", "Comment1",
        "FileOne.txt", Side.PARENT, 3, ownerId, timeBase + 2000,
        "First Parent Comment",  new CommentRange(1, 2, 3, 4));
    plc3.setRevId(new RevId("CDEFCDEFCDEFCDEFCDEFCDEFCDEFCDEFCDEFCDEF"));

    List<PatchLineComment> commentsByOwner = Lists.newArrayList();
    commentsByOwner.add(plc1);
    commentsByOwner.add(plc3);
    List<PatchLineComment> commentsByReviewer = Lists.newArrayList();
    commentsByReviewer.add(plc2);

    plca.upsert(commentsByOwner);
    expectLastCall().anyTimes();
    plca.upsert(commentsByReviewer);
    expectLastCall().anyTimes();

    expect(plca.publishedByPatchSet(psId1))
        .andAnswer(results(plc1, plc2, plc3)).anyTimes();
    expect(plca.publishedByPatchSet(psId2))
        .andAnswer(results()).anyTimes();
    replay(db, plca);

    ChangeUpdate update = newUpdate(change, changeOwner);
    update.setPatchSetId(psId1);
    plcUtil.addPublishedComments(db, update, commentsByOwner);
    update.commit();

    update = newUpdate(change, otherUser);
    update.setPatchSetId(psId1);
    plcUtil.addPublishedComments(db, update, commentsByReviewer);
    update.commit();

    ChangeControl ctl = stubChangeControl(change);
    revRes1 = new RevisionResource(new ChangeResource(ctl), ps1);
    revRes2 = new RevisionResource(new ChangeResource(ctl), ps2);
  }

  private ChangeControl stubChangeControl(Change c) throws OrmException {
    return TestChanges.stubChangeControl(repoManager, c, changeOwner);
  }

  private Change newChange() {
    return TestChanges.newChange(project, changeOwner);
  }

  private ChangeUpdate newUpdate(Change c, final IdentifiedUser user) throws Exception {
    return TestChanges.newUpdate(injector, repoManager, c, user);
  }

  @Test
  public void testListComments() throws Exception {
    // test ListComments for patch set 1
    assertListComments(injector, revRes1, ImmutableMap.of(
        "FileOne.txt", Lists.newArrayList(plc3, plc1, plc2)));

    // test ListComments for patch set 2
    assertListComments(injector, revRes2,
        Collections.<String, ArrayList<PatchLineComment>>emptyMap());
  }

  @Test
  public void testGetComment() throws Exception {
    // test GetComment for existing comment
    assertGetComment(injector, revRes1, plc1, plc1.getKey().get());

    // test GetComment for non-existent comment
    assertGetComment(injector, revRes1, null, "BadComment");
  }

  private static IAnswer<ResultSet<PatchLineComment>> results(
      final PatchLineComment... comments) {
    return new IAnswer<ResultSet<PatchLineComment>>() {
      @Override
      public ResultSet<PatchLineComment> answer() throws Throwable {
        return new ListResultSet<>(Lists.newArrayList(comments));
      }};
  }

  private static void assertGetComment(Injector inj, RevisionResource res,
      PatchLineComment expected, String uuid) throws Exception {
    GetComment getComment = inj.getInstance(GetComment.class);
    Comments comments = inj.getInstance(Comments.class);
    try {
      CommentResource commentRes = comments.parse(res, IdString.fromUrl(uuid));
      if (expected == null) {
        fail("Expected no comment");
      }
      CommentInfo actual = getComment.apply(commentRes);
      assertComment(expected, actual);
    } catch (ResourceNotFoundException e) {
      if (expected != null) {
        fail("Expected to find comment");
      }
    }
  }

  private static void assertListComments(Injector inj, RevisionResource res,
      Map<String, ArrayList<PatchLineComment>> expected) throws Exception {
    Comments comments = inj.getInstance(Comments.class);
    RestReadView<RevisionResource> listView =
        (RestReadView<RevisionResource>) comments.list();
    @SuppressWarnings("unchecked")
    Map<String, List<CommentInfo>> actual =
        (Map<String, List<CommentInfo>>) listView.apply(res);
    assertNotNull(actual);
    assertEquals(expected.size(), actual.size());
    assertEquals(expected.keySet(), actual.keySet());
    for (Map.Entry<String, ArrayList<PatchLineComment>> entry : expected.entrySet()) {
      List<PatchLineComment> expectedComments = entry.getValue();
      List<CommentInfo> actualComments = actual.get(entry.getKey());
      assertNotNull(actualComments);
      assertEquals(expectedComments.size(), actualComments.size());
      for (int i = 0; i < expectedComments.size(); i++) {
        assertComment(expectedComments.get(i), actualComments.get(i));
      }
    }
  }

  private static void assertComment(PatchLineComment plc, CommentInfo ci) {
    assertEquals(plc.getKey().get(), ci.id);
    assertEquals(plc.getParentUuid(), ci.inReplyTo);
    assertEquals(plc.getMessage(), ci.message);
    assertNotNull(ci.author);
    assertEquals(plc.getAuthor(), ci.author._id);
    assertEquals(plc.getLine(), (int) ci.line);
    assertEquals(plc.getSide() == 0 ? Side.PARENT : Side.REVISION,
        Objects.firstNonNull(ci.side, Side.REVISION));
    assertEquals(TimeUtil.roundTimestampToSecond(plc.getWrittenOn()),
        TimeUtil.roundTimestampToSecond(ci.updated));
    assertEquals(plc.getRange(), ci.range);
  }

  private static PatchLineComment newPatchLineComment(PatchSet.Id psId,
      String uuid, String inReplyToUuid, String filename, Side side, int line,
      Account.Id authorId, long millis, String message, CommentRange range) {
    Patch.Key p = new Patch.Key(psId, filename);
    PatchLineComment.Key id = new PatchLineComment.Key(p, uuid);
    PatchLineComment plc =
        new PatchLineComment(id, line, authorId, inReplyToUuid, TimeUtil.nowTs());
    plc.setMessage(message);
    plc.setRange(range);
    plc.setSide(side == Side.PARENT ? (short) 0 : (short) 1);
    plc.setStatus(Status.PUBLISHED);
    plc.setWrittenOn(new Timestamp(millis));
    return plc;
  }
}
