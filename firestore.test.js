const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const crypto = require("node:crypto");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const OWNER_EMAIL = "konami123mw@gmail.com";
const ADMIN_EMAIL = "admin1@gmail.com";
const USER_A_EMAIL = "alice@gmail.com";
const USER_B_EMAIL = "bob@gmail.com";

function sha256(str) {
  return crypto.createHash("sha256").update(str.trim().toLowerCase()).digest("hex");
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

test("Unauthenticated user: cannot read admins/{email}, config/github, or conversations", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("admins").doc(OWNER_EMAIL).get());
  await assertFails(unauthDb.collection("config").doc("github").get());
  await assertFails(unauthDb.collection("conversations").doc("a_b").get());
  await assertFails(unauthDb.collection("emailLookup").doc(sha256(USER_A_EMAIL)).get());
});

test("Any signed-in user: can read their own admins/{email} doc even if it does not exist", async () => {
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(aliceDb.collection("admins").doc(USER_A_EMAIL).get());
});

test("Signed-in user: cannot read another user's admins/{email} doc or list admins", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
  });
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  await assertFails(aliceDb.collection("admins").doc(ADMIN_EMAIL).get());
  await assertFails(aliceDb.collection("admins").get());
});

test("Unverified email token: cannot access admin-only config/github even if allowlisted", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("config").doc("github").set({ token: "secret-token" });
  });
  const unverifiedAdminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: false }).firestore();
  await assertFails(unverifiedAdminDb.collection("config").doc("github").get());
});

test("Non-admin signed-in user: cannot read or write config/github or config/appTheme", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("config").doc("github").set({ token: "secret-token" });
  });
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  await assertFails(aliceDb.collection("config").doc("github").get());
  await assertFails(aliceDb.collection("config").doc("github").set({ token: "hacked" }));
  await assertFails(aliceDb.collection("config").doc("appTheme").set({ id: "hacked" }));
});

test("Approved admin: can read/write config/github and admins, and non-admins remain blocked", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("config").doc("github").set({ token: "shared-token" });
  });
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext("uid_bob", { email: USER_B_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(adminDb.collection("admins").doc(ADMIN_EMAIL).get());
  await assertSucceeds(adminDb.collection("config").doc("github").get());
  await assertSucceeds(adminDb.collection("config").doc("github").set({ token: "changed" }));
  await assertSucceeds(adminDb.collection("admins").doc(USER_A_EMAIL).set({ approved: true }));
  await assertFails(bobDb.collection("admins").doc(USER_B_EMAIL).set({ approved: true }));
});

test("Owner konami123mw@gmail.com: always treated as admin and can read/write config/github and admins", async () => {
  const ownerDb = testEnv.authenticatedContext("uid_owner", { email: OWNER_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(ownerDb.collection("admins").doc(ADMIN_EMAIL).set({ approved: true }));
  await assertSucceeds(ownerDb.collection("config").doc("github").set({ token: "ghp_owner_token" }));
  await assertSucceeds(ownerDb.collection("config").doc("github").get());
  await assertSucceeds(ownerDb.collection("admins").get());
});

test("emailLookup & usernames: exact get works, directory list denied, strict format enforced", async () => {
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const aliceHash = sha256(USER_A_EMAIL);

  // Can write hashed lookup without raw email
  await assertSucceeds(aliceDb.collection("emailLookup").doc(aliceHash).set({ uid: "uid_alice", updatedAt: 1000 }));
  // Cannot store raw email or wrong hash or spoof uid
  await assertFails(aliceDb.collection("emailLookup").doc(aliceHash).set({ uid: "uid_alice", email: USER_A_EMAIL }));
  await assertFails(aliceDb.collection("emailLookup").doc("not_a_sha256").set({ uid: "uid_alice" }));
  await assertFails(aliceDb.collection("emailLookup").doc(sha256(USER_B_EMAIL)).set({ uid: "uid_bob" }));
  // Exact get works, collection list denied
  await assertSucceeds(aliceDb.collection("emailLookup").doc(aliceHash).get());
  await assertFails(aliceDb.collection("emailLookup").get());

  // Usernames: 3..20 chars [a-z0-9_]
  await assertSucceeds(aliceDb.collection("usernames").doc("alice_dev").set({ uid: "uid_alice", createdAt: 1000 }));
  await assertFails(aliceDb.collection("usernames").doc("ab").set({ uid: "uid_alice" })); // too short
  await assertFails(aliceDb.collection("usernames").doc("a".repeat(21)).set({ uid: "uid_alice" })); // too long
  await assertFails(aliceDb.collection("usernames").doc("Alice").set({ uid: "uid_alice" })); // uppercase
  await assertFails(aliceDb.collection("usernames").get()); // listing denied
});

test("users/{uid}: non-admin cannot inject role/email/badges/frame/gif, large WebP <=220KB succeeds, admin manages badges/gif", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
  });
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  // Forbidden fields at create
  await assertFails(aliceDb.collection("users").doc("uid_alice").set({ uid: "uid_alice", role: "admin" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").set({ uid: "uid_alice", email: USER_A_EMAIL }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").set({ uid: "uid_alice", badges: ["موثّق"] }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").set({ uid: "uid_alice", frame: "gold-admin" }));

  // Normal profile create with ~90KB WebP data URL succeeds
  const bigWebp = "data:image/webp;base64," + "A".repeat(90000);
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").set({
    uid: "uid_alice",
    username: "alice_dev",
    usernameLower: "alice_dev",
    usernameChosen: true,
    displayName: "Alice",
    bio: "Hello",
    photoURL: bigWebp,
    bannerURL: bigWebp,
    links: ["https://github.com/alice"],
    profileAccent: "#8B7CFF",
    hideCommentsCount: false,
    blocked: false,
    publisher: false,
    badges: [],
    frame: "",
    customTitle: "",
    profileGradient: "",
    createdAt: 1000,
    updatedAt: 1000,
  }));

  // Normal user can update their bio, links, accent, hideCommentsCount
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").update({
    bio: "Updated bio",
    links: ["https://github.com/alice", "https://t.me/alice"],
    profileAccent: "#FF4D8D",
    hideCommentsCount: true,
    updatedAt: 2000,
  }));

  // Normal user cannot tamper with badges, blocked, links (>3), non-https link, or GIF banner
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ badges: ["موثّق"] }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ links: ["http://insecure.com"] }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({
    links: ["https://a.com", "https://b.com", "https://c.com", "https://d.com"],
  }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({
    bannerURL: "data:image/gif;base64,R0lGODlhAQABAAAAACw=",
  }));

  // Admin CAN grant badges, frame, customTitle, and GIF banner to admin profile
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").get());
  await assertSucceeds(adminDb.collection("users").doc("uid_alice").update({
    badges: ["موثّق", "مبدع"],
    frame: "neon",
    customTitle: "مصمم مميز",
  }));
  await assertSucceeds(adminDb.collection("users").doc("uid_admin").set({
    uid: "uid_admin",
    displayName: "Admin",
    bannerURL: "data:image/gif;base64,R0lGODlhAQABAAAAACw=",
    badges: [],
    frame: "gold-admin",
    customTitle: "المشرف",
    profileGradient: "linear-gradient(135deg,#F59E0B,#EF4444)",
    blocked: false,
    publisher: false,
    createdAt: 1000,
  }));
});

test("conversations & messages: private to participants, read receipts restricted, personal block blocks messages, admins cannot snoop non-reported chats", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("users").doc("uid_alice").set({ uid: "uid_alice", blocked: false });
    await ctx.firestore().collection("users").doc("uid_bob").set({ uid: "uid_bob", blocked: false });
  });

  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext("uid_bob", { email: USER_B_EMAIL, email_verified: true }).firestore();
  const charlieDb = testEnv.authenticatedContext("uid_charlie", { email: "c@gmail.com", email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  const convId = "uid_alice_uid_bob";
  // Non-existent doc get works cleanly for participants check
  await assertSucceeds(aliceDb.collection("conversations").doc(convId).get());

  // Cannot create conversation spoofed without self or with wrong participant count
  await assertFails(charlieDb.collection("conversations").doc(convId).set({
    participants: ["uid_alice", "uid_bob"],
    participantA: "uid_alice",
    participantB: "uid_bob",
  }));

  // Alice creates conversation & sends message to Bob
  await assertSucceeds(aliceDb.collection("conversations").doc(convId).set({
    participants: ["uid_alice", "uid_bob"],
    participantA: "uid_alice",
    participantB: "uid_bob",
    lastMessage: "Hi Bob",
    lastAt: 1000,
    updatedAt: 1000,
    hasReportedMessage: false,
    unreadBy: { uid_alice: false, uid_bob: true },
  }));
  await assertSucceeds(aliceDb.collection("conversations").doc(convId).collection("messages").doc("m1").set({
    text: "Hi Bob",
    senderId: "uid_alice",
    createdAt: 1000,
    read: false,
  }));

  // Outsider & even Admin CANNOT read non-reported conversation or messages
  await assertFails(charlieDb.collection("conversations").doc(convId).get());
  await assertFails(adminDb.collection("conversations").doc(convId).get());
  await assertFails(adminDb.collection("conversations").doc(convId).collection("messages").doc("m1").get());

  // Bob can read and mark read=true, but cannot edit message text
  await assertSucceeds(bobDb.collection("conversations").doc(convId).collection("messages").doc("m1").get());
  await assertFails(bobDb.collection("conversations").doc(convId).collection("messages").doc("m1").update({ text: "Tampered" }));
  await assertSucceeds(bobDb.collection("conversations").doc(convId).collection("messages").doc("m1").update({ read: true }));

  // Bob blocks Alice -> Alice cannot send new messages to Bob
  await assertSucceeds(bobDb.collection("users").doc("uid_bob").collection("blockedUsers").doc("uid_alice").set({ uid: "uid_alice", createdAt: 1500 }));
  await assertFails(aliceDb.collection("conversations").doc(convId).collection("messages").doc("m2").set({
    text: "Hello again",
    senderId: "uid_alice",
    createdAt: 2000,
    read: false,
  }));

  // Once Bob reports & marks hasReportedMessage: true, Admin can read
  await assertSucceeds(bobDb.collection("conversations").doc(convId).update({ hasReportedMessage: true }));
  await assertSucceeds(adminDb.collection("conversations").doc(convId).get());
  await assertSucceeds(adminDb.collection("conversations").doc(convId).collection("messages").doc("m1").get());
  // Non-admin cannot unset hasReportedMessage back to false
  await assertFails(aliceDb.collection("conversations").doc(convId).update({ hasReportedMessage: false }));
});

test("conversations queries & legacy docs: whereArrayContains succeeds for member inbox/unread badge, unfiltered list fails, legacy conv without participantA/B works", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc("uid_alice").set({ uid: "uid_alice", blocked: false });
    await ctx.firestore().collection("users").doc("uid_bob").set({ uid: "uid_bob", blocked: false });
    await ctx.firestore().collection("conversations").doc("uid_alice_uid_bob").set({
      participants: ["uid_alice", "uid_bob"],
      lastMessage: "Legacy hello",
      lastAt: 1000,
      unreadBy: { uid_alice: true, uid_bob: false },
    });
    await ctx.firestore().collection("conversations").doc("uid_bob_uid_charlie").set({
      participants: ["uid_bob", "uid_charlie"],
      participantA: "uid_bob",
      participantB: "uid_charlie",
      lastMessage: "Private",
      lastAt: 2000,
    });
  });

  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const charlieDb = testEnv.authenticatedContext("uid_charlie", { email: "c@gmail.com", email_verified: true }).firestore();

  // Exact queries used by startUnreadMessagesListener & startMemberInboxListener MUST succeed
  await assertSucceeds(
    aliceDb.collection("conversations").where("participants", "array-contains", "uid_alice").get()
  );
  await assertSucceeds(
    aliceDb.collection("conversations").where("participants", "array-contains", "uid_alice").limit(100).get()
  );
  // Unfiltered list or querying someone else's chats MUST fail
  await assertFails(aliceDb.collection("conversations").get());
  await assertFails(
    charlieDb.collection("conversations").where("participants", "array-contains", "uid_alice").get()
  );

  // Reading & sending message in legacy conversation lacking participantA/participantB succeeds for participant
  await assertSucceeds(aliceDb.collection("conversations").doc("uid_alice_uid_bob").collection("messages").limit(50).get());
  await assertSucceeds(aliceDb.collection("conversations").doc("uid_alice_uid_bob").collection("messages").add({
    text: "Reply on legacy chat",
    senderId: "uid_alice",
    createdAt: 3000,
    read: false,
  }));
  // Spoofing senderId fails even for participant
  await assertFails(aliceDb.collection("conversations").doc("uid_alice_uid_bob").collection("messages").add({
    text: "Spoofed",
    senderId: "uid_bob",
    createdAt: 3001,
    read: false,
  }));
  // Marking unreadBy false works
  await assertSucceeds(aliceDb.collection("conversations").doc("uid_alice_uid_bob").update({
    "unreadBy.uid_alice": false,
  }));
});

test("legacy users/{uid} & claimInitialUsername: user can claim username on fresh or legacy doc without escalating privileges", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    // Legacy user document missing uid, having old email/role and null fields
    await ctx.firestore().collection("users").doc("uid_legacy").set({
      email: "legacy@gmail.com",
      role: "user",
      displayName: "Legacy User",
      links: null,
      badges: null,
      photoURL: "http://legacy.example.com/p.jpg",
    });
  });

  const legacyDb = testEnv.authenticatedContext("uid_legacy", { email: "legacy@gmail.com", email_verified: true }).firestore();
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();

  // Claiming username doc + updating legacy user doc with merge succeeds
  await assertSucceeds(legacyDb.collection("usernames").doc("legacy_user").set({ uid: "uid_legacy", createdAt: 1000 }));
  await assertSucceeds(legacyDb.collection("users").doc("uid_legacy").set({
    uid: "uid_legacy",
    username: "legacy_user",
    usernameLower: "legacy_user",
    usernameChosen: true,
    displayName: "Legacy Updated",
    updatedAt: 2000,
  }, { merge: true }));

  // Idempotent update on own username reservation succeeds; Alice cannot steal it
  await assertSucceeds(legacyDb.collection("usernames").doc("legacy_user").set({ uid: "uid_legacy", createdAt: 2000 }));
  await assertFails(aliceDb.collection("usernames").doc("legacy_user").set({ uid: "uid_alice", createdAt: 3000 }));

  // Legacy user still CANNOT modify role, blocked, publisher, badges, or email
  await assertFails(legacyDb.collection("users").doc("uid_legacy").update({ role: "admin" }));
  await assertFails(legacyDb.collection("users").doc("uid_legacy").update({ publisher: true }));
  await assertFails(legacyDb.collection("users").doc("uid_legacy").update({ email: "hacked@gmail.com" }));
});

test("submissions, notifications & auditLog: open submissions for normal users, notifications recipient-only, auditLog admin append-only", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("users").doc("uid_alice").set({ uid: "uid_alice", blocked: false, publisher: false });
    await ctx.firestore().collection("users").doc("uid_blocked").set({ uid: "uid_blocked", blocked: true });
    await ctx.firestore().collection("themes").doc("theme_pinned").set({ pinned: true, ratingAvg: 4.5, ratingCount: 2 });
  });

  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext("uid_bob", { email: USER_B_EMAIL, email_verified: true }).firestore();
  const blockedDb = testEnv.authenticatedContext("uid_blocked", { email: "bad@gmail.com", email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  // Rating merge on already-pinned theme succeeds
  await assertSucceeds(aliceDb.collection("themes").doc("theme_pinned").set({
    ratingAvg: 4.67,
    ratingCount: 3,
    updatedAt: 1500,
  }, { merge: true }));

  // Regular user (publisher: false) CAN submit a theme
  await assertSucceeds(aliceDb.collection("submissions").doc("sub1").set({
    publisherUid: "uid_alice",
    title: "Dark Cyber",
    status: "pending",
    createdAt: 1000,
  }));
  // Client queries for submissions work
  await assertSucceeds(aliceDb.collection("submissions").where("publisherUid", "==", "uid_alice").get());
  await assertSucceeds(adminDb.collection("submissions").where("status", "==", "pending").get());
  // Blocked user cannot submit; regular user cannot self-approve
  await assertFails(blockedDb.collection("submissions").doc("sub2").set({
    publisherUid: "uid_blocked",
    title: "Spam",
    status: "pending",
  }));
  await assertFails(aliceDb.collection("submissions").doc("sub3").set({
    publisherUid: "uid_alice",
    title: "Bypass",
    status: "approved",
  }));
  await assertFails(aliceDb.collection("submissions").doc("sub1").update({ status: "approved" }));
  // Other user cannot read Alice's submission
  await assertFails(bobDb.collection("submissions").doc("sub1").get());

  // Admin approves submission, writes notification for Alice, and logs auditLog
  await assertSucceeds(adminDb.collection("submissions").doc("sub1").update({ status: "approved" }));
  await assertSucceeds(adminDb.collection("notifications").doc("uid_alice").collection("items").doc("n1").set({
    type: "submission_approved",
    title: "تم قبول ثيمك",
    body: "تم نشر Dark Cyber",
    read: false,
    createdAt: 2000,
  }));
  await assertSucceeds(adminDb.collection("auditLog").doc("log1").set({
    actorUid: "uid_admin",
    action: "approve_submission",
    targetId: "sub1",
    createdAt: 2000,
  }));

  // Non-admin cannot forge notifications or auditLog, and admin cannot delete auditLog
  await assertFails(aliceDb.collection("notifications").doc("uid_alice").collection("items").doc("n2").set({ title: "fake" }));
  await assertFails(bobDb.collection("notifications").doc("uid_alice").collection("items").doc("n1").get());
  await assertSucceeds(aliceDb.collection("notifications").doc("uid_alice").collection("items").doc("n1").update({ read: true }));
  await assertFails(adminDb.collection("auditLog").doc("log1").delete());
});

test("Club posts, media, reactions, polls, auto-hide reports, follows, bookmarks, and moderation", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("users").doc("uid_alice").set({ uid: "uid_alice", blocked: false, muted: false });
    await ctx.firestore().collection("users").doc("uid_bob").set({ uid: "uid_bob", blocked: false, muted: false });
    await ctx.firestore().collection("users").doc("uid_muted").set({ uid: "uid_muted", blocked: false, muted: true, mutedUntil: 0 });
    await ctx.firestore().collection("config").doc("moderation").set({
      clubEnabled: true,
      bannedWords: ["كلمة_ممنوعة"],
      maxDailyPosts: 10
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext("uid_bob", { email: USER_B_EMAIL, email_verified: true }).firestore();
  const mutedDb = testEnv.authenticatedContext("uid_muted", { email: "muted@gmail.com", email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  // Guest can read config/moderation & visible posts, cannot write
  await assertSucceeds(unauthDb.collection("config").doc("moderation").get());
  await assertFails(aliceDb.collection("config").doc("moderation").set({ clubEnabled: false }));
  await assertSucceeds(adminDb.collection("config").doc("moderation").set({ clubEnabled: true }, { merge: true }));

  const basePost = {
    authorUid: "uid_alice",
    authorName: "Alice",
    authorUsername: "alice_dev",
    authorPhoto: "",
    authorFrame: "",
    authorBadges: [],
    authorIsAdmin: false,
    authorIsPublisher: false,
    text: "أهلاً بكم في النادي!",
    category: "عام",
    themeRef: "",
    videoUrl: "",
    videoDurationSec: 0,
    mediaCount: 1,
    poll: {
      question: "أفضل ثيم؟",
      options: [{ text: "داكن", votes: 0 }, { text: "فاتح", votes: 0 }],
      totalVotes: 0
    },
    likesCount: 0,
    commentsCount: 0,
    reportsCount: 0,
    engagementScore: 0,
    reactions: { like: 0, love: 0, haha: 0, wow: 0, fire: 0 },
    mentions: [],
    status: "visible",
    pinned: false,
    isAnnouncement: false,
    commentsLocked: false,
    createdAt: 10000,
    updatedAt: 10000
  };

  // Muted user cannot create post; regular user cannot create pre-pinned or spoofed authorUid
  await assertFails(mutedDb.collection("posts").doc("p_muted").set({ ...basePost, authorUid: "uid_muted" }));
  await assertFails(aliceDb.collection("posts").doc("p_pin").set({ ...basePost, pinned: true }));
  await assertFails(aliceDb.collection("posts").doc("p_spoof").set({ ...basePost, authorUid: "uid_bob" }));

  // Alice creates post + media subdoc atomically via batch
  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("posts").doc("p1"), basePost);
  batch.set(aliceDb.collection("posts").doc("p1").collection("media").doc("0"), {
    index: 0,
    authorUid: "uid_alice",
    dataUrl: "data:image/webp;base64,UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=",
    createdAt: 10000
  });
  await assertSucceeds(batch.commit());

  // Guest can query visible posts; unfiltered list fails
  await assertSucceeds(unauthDb.collection("posts").where("status", "==", "visible").get());
  await assertFails(unauthDb.collection("posts").get());

  // Bob likes & reacts (+1 likesCount) and cannot tamper with status or pinned
  await assertSucceeds(bobDb.collection("posts").doc("p1").collection("likes").doc("uid_bob").set({
    uid: "uid_bob",
    reaction: "fire",
    createdAt: 11000
  }));
  await assertSucceeds(bobDb.collection("posts").doc("p1").update({
    likesCount: 1,
    reactions: { like: 0, love: 0, haha: 0, wow: 0, fire: 1 },
    engagementScore: 1,
    updatedAt: 11000
  }));
  await assertFails(bobDb.collection("posts").doc("p1").update({ likesCount: 50, updatedAt: 11001 }));
  await assertFails(bobDb.collection("posts").doc("p1").update({ status: "hidden", updatedAt: 11002 }));

  // Bob votes once on poll atomically; second vote rejected
  const voteBatch = bobDb.batch();
  voteBatch.set(bobDb.collection("posts").doc("p1").collection("pollVotes").doc("uid_bob"), {
    uid: "uid_bob",
    optionIndex: 0,
    createdAt: 11500
  });
  voteBatch.update(bobDb.collection("posts").doc("p1"), {
    poll: {
      question: "أفضل ثيم؟",
      options: [{ text: "داكن", votes: 1 }, { text: "فاتح", votes: 0 }],
      totalVotes: 1
    },
    updatedAt: 11500
  });
  await assertSucceeds(voteBatch.commit());
  await assertFails(bobDb.collection("posts").doc("p1").collection("pollVotes").doc("uid_bob").set({
    uid: "uid_bob",
    optionIndex: 1,
    createdAt: 11600
  }));

  // Bob sends notification to Alice for post_like (valid), cannot forge submission_approved
  await assertSucceeds(bobDb.collection("notifications").doc("uid_alice").collection("items").doc("notif_like").set({
    type: "post_like",
    title: "إعجاب بمنشورك",
    body: "أعجب Bob بمنشورك",
    actorUid: "uid_bob",
    actorName: "Bob",
    postId: "p1",
    themeId: "",
    submissionId: "",
    read: false,
    createdAt: 11700
  }));
  await assertFails(bobDb.collection("notifications").doc("uid_alice").collection("items").doc("notif_fake").set({
    type: "submission_approved",
    title: "fake",
    body: "fake",
    actorUid: "uid_bob",
    actorName: "Bob",
    postId: "",
    themeId: "",
    submissionId: "",
    read: false,
    createdAt: 11700
  }));

  // Follows & Bookmarks isolation
  await assertSucceeds(bobDb.collection("follows").doc("uid_bob").collection("following").doc("uid_alice").set({ uid: "uid_alice", createdAt: 12000 }));
  await assertSucceeds(bobDb.collection("follows").doc("uid_alice").collection("followers").doc("uid_bob").set({ uid: "uid_bob", createdAt: 12000 }));
  await assertFails(bobDb.collection("follows").doc("uid_alice").collection("following").doc("uid_bob").set({ uid: "uid_bob", createdAt: 12000 }));
  await assertSucceeds(bobDb.collection("bookmarks").doc("uid_bob").collection("posts").doc("p1").set({ postId: "p1", createdAt: 12000 }));
  await assertFails(aliceDb.collection("bookmarks").doc("uid_bob").collection("posts").doc("p1").get());

  // 5th distinct report auto-hides post
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("posts").doc("p1").update({ reportsCount: 4 });
  });
  const repBatch = bobDb.batch();
  repBatch.set(bobDb.collection("posts").doc("p1").collection("postReports").doc("uid_bob"), {
    reporterUid: "uid_bob",
    reason: "سبام",
    createdAt: 13000
  });
  repBatch.update(bobDb.collection("posts").doc("p1"), {
    reportsCount: 5,
    status: "hidden",
    updatedAt: 13000
  });
  await assertSucceeds(repBatch.commit());
  // Hidden post now denied to guest, visible to admin & author Alice
  await assertFails(unauthDb.collection("posts").doc("p1").get());
  await assertSucceeds(adminDb.collection("posts").doc("p1").get());
  await assertSucceeds(aliceDb.collection("posts").doc("p1").get());

  // Admin personal token isolated to own uid
  await assertSucceeds(adminDb.collection("adminTokens").doc("uid_admin").set({ token: "ghp_123", updatedAt: 14000, email: ADMIN_EMAIL }));
  await assertFails(aliceDb.collection("adminTokens").doc("uid_admin").get());
});

test("frames/{frameId} & verified/badgeType/frameId/nameStyling security rules", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("users").doc("uid_alice").set({
      uid: "uid_alice",
      displayName: "Alice",
      blocked: false,
      verified: false,
      badgeType: "",
      frameId: "",
      frameAssigned: [],
      title: "",
      nameColor: "",
      nameGradient: "",
      nameFont: ""
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const ownerDb = testEnv.authenticatedContext("uid_owner", { email: OWNER_EMAIL, email_verified: true }).firestore();

  // Admin can write frames, normal user cannot
  await assertSucceeds(adminDb.collection("frames").doc("public_sunset").set({
    id: "public_sunset",
    name: "غروب ذهبي",
    type: "gradient",
    colors: ["#F97316", "#EC4899"],
    access: "everyone"
  }));
  await assertSucceeds(adminDb.collection("frames").doc("admin_shield_frame").set({
    id: "admin_shield_frame",
    name: "درع المشرف",
    type: "animated",
    colors: ["#D4AF37", "#FDE68A"],
    access: "admins"
  }));
  await assertSucceeds(adminDb.collection("frames").doc("owner_crown_frame").set({
    id: "owner_crown_frame",
    name: "تاج المالك",
    type: "animated",
    colors: ["#D4AF37", "#7C5CFF"],
    access: "owner"
  }));
  await assertSucceeds(adminDb.collection("frames").doc("vip_assigned_frame").set({
    id: "vip_assigned_frame",
    name: "إطار كبار الشخصيات",
    type: "conic",
    colors: ["#00F5D4", "#FF2E93"],
    access: "assigned"
  }));
  await assertFails(aliceDb.collection("frames").doc("hacked_frame").set({
    id: "hacked_frame",
    name: "Hack",
    type: "solid",
    colors: ["#FFFFFF"],
    access: "everyone"
  }));

  // Public read on frames works even unauthenticated
  await assertSucceeds(unauthDb.collection("frames").doc("public_sunset").get());

  // Normal user CANNOT spoof verified, badgeType, frameAssigned, title, nameColor, nameGradient, or nameFont
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ verified: true }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ badgeType: "verified" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ frameAssigned: ["vip_assigned_frame"] }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ title: "المالك" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ nameColor: "#D4AF37" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ nameGradient: "linear-gradient(90deg,#D4AF37,#7C5CFF)" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ nameFont: "Playfair Display" }));

  // Normal user CAN equip an 'everyone' frame
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").update({ frameId: "public_sunset" }));
  // Normal user CANNOT equip 'admins', 'owner', non-existent, or unassigned 'assigned' frame
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ frameId: "admin_shield_frame" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ frameId: "owner_crown_frame" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ frameId: "vip_assigned_frame" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ frameId: "missing_frame" }));

  // Admin assigns vip_assigned_frame & verified status to Alice
  await assertSucceeds(adminDb.collection("users").doc("uid_alice").update({
    verified: true,
    badgeType: "verified",
    frameAssigned: ["vip_assigned_frame"],
    title: "مبدع موثّق",
    nameColor: "#D4AF37",
    nameFont: "Cairo"
  }));

  // Now Alice CAN equip her assigned frame and can also clear frameId
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").update({ frameId: "vip_assigned_frame" }));
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").update({ frameId: "" }));

  // Owner can create/update profile and select owner_crown_frame
  await assertSucceeds(ownerDb.collection("users").doc("uid_owner").set({
    uid: "uid_owner",
    displayName: "Owner",
    frameId: "owner_crown_frame",
    verified: true
  }));
});

test("publishLogs/{logId}: admin append-only create & read, non-admin denied, update/delete denied", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  await assertSucceeds(adminDb.collection("publishLogs").doc("plog_1").set({
    actorUid: "uid_admin",
    actorLabel: "Admin",
    themeId: "cyber-101",
    themeName: "Cyber Glow",
    mode: "new",
    status: "success",
    version: "v1.0",
    details: "1 variant",
    createdAt: 20000
  }));

  await assertSucceeds(adminDb.collection("publishLogs").doc("plog_1").get());
  await assertFails(unauthDb.collection("publishLogs").doc("plog_1").get());
  await assertFails(aliceDb.collection("publishLogs").doc("plog_1").get());
  await assertFails(aliceDb.collection("publishLogs").doc("plog_2").set({
    actorUid: "uid_alice",
    themeId: "fake",
    themeName: "Fake",
    mode: "new",
    status: "success",
    createdAt: 20001
  }));
  // Spoofed actorUid rejected
  await assertFails(adminDb.collection("publishLogs").doc("plog_3").set({
    actorUid: "someone_else",
    themeId: "x",
    themeName: "X",
    mode: "new",
    status: "success",
    createdAt: 20002
  }));
  // Append-only: update and delete forbidden even for admin
  await assertFails(adminDb.collection("publishLogs").doc("plog_1").update({ status: "failed" }));
  await assertFails(adminDb.collection("publishLogs").doc("plog_1").delete());
});
