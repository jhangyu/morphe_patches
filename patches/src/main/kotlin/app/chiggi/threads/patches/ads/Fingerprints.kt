package app.chiggi.threads.patches.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
    definingClass = "Lcom/instagram/feed/media/Media;",
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
