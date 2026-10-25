package app.chiggi.threads.patches.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Hook 1a — the single ad-classification chokepoint (located, not patched).
 *
 *   // 510406907: LX/9A5;->A00(LX/9A4;)Z   510406906: LX/9aC;->A00(LX/9aB;)Z
 *   public static final boolean A00(X.9A4 media) {
 *       ...
 *       return media.tree.getTree("injected".hashCode()) != null;
 *   }
 *
 * Meta's GraphQL accessors address fields by `String.hashCode()` of the field name, so
 * `"injected".hashCode()` (283678192) is a constant that survives R8 renaming and differs per build
 * only if the server field is renamed. Several static predicates read `injected`; the classifier is
 * the one whose result IS `injected != null`, i.e. the `injected` lookup is the last call it makes.
 * (The others go on to read another field, e.g. `is_pharma_vertical_ad`.)
 */
private const val INJECTED_FIELD_HASH = 283678192 // "injected".hashCode()

internal object InjectedAdClassifierFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L"),
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val injectedIndex = instructions.indexOfLast {
            (it as? NarrowLiteralInstruction)?.narrowLiteral == INJECTED_FIELD_HASH
        }
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 && injectedIndex >= 0 &&
            instructions.drop(injectedIndex + 1).count { (it as? ReferenceInstruction)?.reference is MethodReference } == 1
    },
)

/**
 * Hook 1b — the canonical "is this media an ad/sponsored" boolean.
 *
 *   // Lcom/instagram/feed/media/Media;   (named DED in 510406907, DES in 510406906)
 *   public final boolean DED() {
 *       return X.9A5.A00(new X.9A4(this.A04));
 *   }
 *
 * `Media.DED()Z` is a pure predicate (no side effects) routed through the classifier of hook 1a,
 * and is consumed by every feed/clip surface to decide whether to render ad chrome (CTA bar,
 * "Sponsored" label, click-through). Forcing it to return false makes every media report as
 * organic, stripping ad treatment.
 *
 * Anchored on the NAMED class `com.instagram.feed.media.Media` plus "returns the classifier result
 * directly" (invoke-static classifier, move-result, return). Other Media predicates also call the
 * classifier but combine it with more logic. No obfuscated name is pinned: the R8 name differs even
 * between the per-ABI builds of the same version.
 */
internal fun mediaIsAdFingerprint(classifier: Method) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    definingClass = MEDIA_CLASS,
    custom = { method, _ ->
        val tail = method.implementation?.instructions?.toList().orEmpty().takeLast(3)
        val call = (tail.firstOrNull() as? ReferenceInstruction)?.reference as? MethodReference
        tail.map { it.opcode } == listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.RETURN) &&
            call?.definingClass == classifier.definingClass &&
            call.name == classifier.name &&
            call.parameterTypes == classifier.parameterTypes
    },
)

/**
 * Hook 2 — the Threads feed ad-injection scheduler (true removal, feed-scoped).
 *
 *   // Lcom/instagram/barcelona/feed/data/cache/BarcelonaSpoolFeedCacheHandler;
 *   public final boolean A0g(X.3oS item, int position) {
 *       ... new BarcelonaSpoolFeedCacheHandler$injectAdToFeedSessionAtPosition$1(...) ...
 *   }
 *
 * This is the public entry point that schedules insertion of an ad feed-item (`X.3oS`) into the live
 * feed session at a position. Returning false short-circuits before the async injector is posted, so
 * the ad slot is never created (no blank card). The return value already signals "did we inject",
 * which callers tolerate.
 *
 * Anchored on the NAMED class `BarcelonaSpoolFeedCacheHandler` plus the descriptive, stable inner
 * lambda class name `injectAdToFeedSessionAtPosition$1` (kept by Kotlin), rather than the obfuscated
 * method name `A0g`, so it survives method-name reshuffling.
 */
private const val FEED_CACHE_HANDLER_CLASS =
    "Lcom/instagram/barcelona/feed/data/cache/BarcelonaSpoolFeedCacheHandler;"
private const val INJECT_AD_ANCHOR = "injectAdToFeedSessionAtPosition"

internal const val MEDIA_CLASS = "Lcom/instagram/feed/media/Media;"
internal const val FEED_ITEMS_KEY = "feed_items"
internal const val LEGACY_ITEMS_KEY = "items"
private const val AD_INSERTIONS_KEY = "max_num_possible_ad_insertions"

/**
 * Index of the `iput-object` that stores the List parsed for JSON [key], i.e. the first List-field
 * store after the `const-string key` comparison. -1 if absent.
 */
internal fun listStoreIndexAfterKey(method: Method, key: String): Int {
    val instructions = method.implementation?.instructions?.toList().orEmpty()
    val keyIndex = instructions.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == key
    }
    if (keyIndex < 0) return -1
    val storeOffset = instructions.drop(keyIndex + 1).indexOfFirst {
        it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == "Ljava/util/List;"
    }
    return if (storeOffset < 0) -1 else keyIndex + 1 + storeOffset
}

/**
 * Hook 3 — the feed response JSON parser (server-inlined ads).
 *
 *   // 510406906: LX/3gb;   452/512207626: LX/4nb;
 *   public final Object unsafeParseFromJson(JsonParser p) {
 *       ... if ("feed_items".equals(field)) { list = new ArrayList(); ...add(FeedItemParser.parse(p)); response.feedItems = list; }
 *       ... if ("items".equals(field))      { list = new ArrayList(); ...add(Media.parse(p));          response.items = list; }
 *       ... "max_num_possible_ad_insertions" ...
 *   }
 *
 * The response object's two item lists are written only here (feed_items = feed items, items = the
 * legacy Media list that the response wrapper converts to feed items when feed_items is absent).
 * Anchored on JSON keys, which are wire format and survive R8.
 */
internal object FeedResponseParserFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
    custom = { method, _ ->
        method.parameterTypes.size == 1 &&
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == AD_INSERTIONS_KEY
            } == true &&
            listStoreIndexAfterKey(method, FEED_ITEMS_KEY) >= 0 &&
            listStoreIndexAfterKey(method, LEGACY_ITEMS_KEY) >= 0
    },
)

/**
 * The feed item's media getter (`X.3qC.A05()` in 510406906, `X.9cz.A05()` in 452): the only
 * instance no-arg method of the feed item class returning Media. A media item returns itself, a
 * Threads thread item returns its first post. The feed item class is taken from hook 2's first
 * parameter, so no obfuscated name is pinned.
 */
internal fun feedItemMediaFingerprint(feedItemClass: String) = Fingerprint(
    definingClass = feedItemClass,
    returnType = MEDIA_CLASS,
    parameters = listOf(),
    custom = { method, classDef ->
        classDef.type == feedItemClass && method.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(method.accessFlags)
    },
)

internal object InjectAdToFeedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    definingClass = FEED_CACHE_HANDLER_CLASS,
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction is ReferenceInstruction &&
                (instruction.reference as? TypeReference)?.type?.contains(INJECT_AD_ANCHOR) == true
        } == true
    },
)
