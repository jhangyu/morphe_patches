package app.chiggi.threads.patches.ads

import app.chiggi.threads.patches.shared.Constants.COMPATIBILITY_THREADS
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** Unmodified copy of the isAd predicate, added to Media so the feed filter can still detect ads. */
private const val ORIGINAL_IS_AD_METHOD = "hideAdsIsAdOriginal"
private const val FEED_FILTER_METHOD = "hideAdsDropAdItems"
private const val FEED_FILTER_REGISTERS = 5 // v0..v3 locals + p0

/**
 * Hides sponsored / "netego" units from the Threads feed using three complementary hooks:
 *
 *  1. Media.isAd()Z -> false : every media reports as organic, stripping ad chrome (the "Sponsored"
 *     label, CTA bar, click-through).
 *  2. BarcelonaSpoolFeedCacheHandler's inject-ad entry point -> false : blocks the client-side
 *     ad-injection scheduler so injected ad slots are never created (true removal, no blank card).
 *  3. Feed response parser : feed items whose media is an ad (GraphQL field `injected` present, checked
 *     with an untouched copy of the isAd predicate) are dropped from the parsed `feed_items` and legacy
 *     `items` lists, so server-inlined ads never reach the feed or its cache.
 *
 * Client-side only; Threads still fetches ads from the server, the patch just refuses to surface them.
 *
 * NOT device-verified by the author — only bytecode application is confirmed. Forcing isAd false is
 * global (also affects non-feed surfaces such as reels); hooks 2 and 3 are feed-scoped.
 */
@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides sponsored posts and injected \"suggested\" netego units from the Threads " +
        "feed. Marks every media as organic, blocks the feed ad-injection scheduler " +
        "(BarcelonaSpoolFeedCacheHandler) and drops ad items from parsed feed responses. Client-side only.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_THREADS)

    execute {
        val isAdFingerprint = mediaIsAdFingerprint(InjectedAdClassifierFingerprint.originalMethod)

        // 3a. Keep the real predicate under a new name before hook 1 overrides it.
        isAdFingerprint.classDef.methods.add(
            MutableMethod(
                ImmutableMethod(
                    MEDIA_CLASS,
                    ORIGINAL_IS_AD_METHOD,
                    emptyList<ImmutableMethodParameter>(),
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(isAdFingerprint.originalMethod.implementation!!),
                ),
            ),
        )

        // 1. Canonical isAd predicate -> always organic.
        isAdFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // 2. Ad-injection scheduler -> never inject (no ad slot created).
        val feedItemClass = InjectAdToFeedFingerprint.originalMethod.parameterTypes.first().toString()
        InjectAdToFeedFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // 3b. Static filter on the parser class: keep every element that is not an ad. Elements that are
        // neither Media nor feed items, and feed items without media, are kept.
        val mediaGetter = feedItemMediaFingerprint(feedItemClass).originalMethod.name
        val parserClass = FeedResponseParserFingerprint.classDef
        val filter = MutableMethod(
            ImmutableMethod(
                parserClass.type,
                FEED_FILTER_METHOD,
                listOf(ImmutableMethodParameter("Ljava/util/List;", null, null)),
                "Ljava/util/List;",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(FEED_FILTER_REGISTERS),
            ),
        )
        filter.addInstructions(
            0,
            """
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                invoke-interface {p0}, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v1
                :next
                invoke-interface {v1}, Ljava/util/Iterator;->hasNext()Z
                move-result v2
                if-eqz v2, :done
                invoke-interface {v1}, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v2
                instance-of v3, v2, $MEDIA_CLASS
                if-eqz v3, :feed_item
                move-object v3, v2
                check-cast v3, $MEDIA_CLASS
                goto :check
                :feed_item
                instance-of v3, v2, $feedItemClass
                if-eqz v3, :keep
                move-object v3, v2
                check-cast v3, $feedItemClass
                invoke-virtual {v3}, $feedItemClass->$mediaGetter()$MEDIA_CLASS
                move-result-object v3
                if-eqz v3, :keep
                :check
                invoke-virtual {v3}, $MEDIA_CLASS->$ORIGINAL_IS_AD_METHOD()Z
                move-result v3
                if-nez v3, :next
                :keep
                invoke-virtual {v0, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                goto :next
                :done
                return-object v0
            """,
        )
        parserClass.methods.add(filter)

        // 3c. Route both parsed item lists through the filter. The store is replaced in place (not
        // preceded by an insert) because the parse loop's exit branch targets the store instruction.
        // Highest index first so the earlier index stays valid.
        val parser = FeedResponseParserFingerprint.method
        listOf(FEED_ITEMS_KEY, LEGACY_ITEMS_KEY)
            .map { listStoreIndexAfterKey(FeedResponseParserFingerprint.originalMethod, it) }
            .sortedDescending()
            .forEach { index ->
                val store = parser.getInstruction<TwoRegisterInstruction>(index)
                val field = (store as ReferenceInstruction).reference as FieldReference
                val list = store.registerA
                parser.replaceInstruction(
                    index,
                    "invoke-static/range {v$list .. v$list}, ${parserClass.type}->$FEED_FILTER_METHOD(Ljava/util/List;)Ljava/util/List;",
                )
                parser.addInstructions(
                    index + 1,
                    """
                        move-result-object v$list
                        iput-object v$list, v${store.registerB}, ${field.definingClass}->${field.name}:${field.type}
                    """,
                )
            }
    }
}
