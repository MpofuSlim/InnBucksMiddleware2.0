package zw.co.innbucks.middleware.kyc;

import zw.co.innbucks.middleware.customer.KycTier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a submission for a tier must carry. Pending compliance's confirmation
 * of the tier requirements against the RBZ rules, these are the app's
 * proposal: {@code standard} = national ID number, both sides of the ID and a
 * selfie; {@code enhanced} = all of that plus a residential address and a
 * photo of a proof of address.
 *
 * <p><b>A submission is self-contained</b>: it carries everything its target
 * tier needs, even when an earlier approved submission already holds some of
 * it. Reusing evidence across submissions would put months-old photos in
 * front of an approver as if they were part of the request being decided;
 * whether to allow it is the review step's decision, not intake's.
 */
public final class KycRequirements {

    public static final String NATIONAL_ID = "national_id";
    public static final String ADDRESS = "address";

    private KycRequirements() {
    }

    /** The images a submission for {@code target} needs, in upload order. */
    public static Set<KycFileKind> fileKinds(KycTier target) {
        return switch (target) {
            case BASIC -> EnumSet.noneOf(KycFileKind.class);
            case STANDARD -> EnumSet.of(KycFileKind.ID_FRONT, KycFileKind.ID_BACK, KycFileKind.SELFIE);
            case ENHANCED -> EnumSet.allOf(KycFileKind.class);
        };
    }

    public static boolean requiresAddress(KycTier target) {
        return target == KycTier.ENHANCED;
    }

    /**
     * Everything the app collects for {@code target}, as wire codes:
     * {@code national_id}, the file kinds, and {@code address} for enhanced.
     */
    public static List<String> needs(KycTier target) {
        List<String> out = new ArrayList<>();
        out.add(NATIONAL_ID);
        for (KycFileKind kind : fileKinds(target)) {
            if (kind == KycFileKind.PROOF_OF_ADDRESS) {
                out.add(ADDRESS);
            }
            out.add(kind.code());
        }
        return out;
    }

    /** Wire codes of {@code kinds}, in declaration order. */
    public static List<String> codes(Collection<KycFileKind> kinds) {
        EnumSet<KycFileKind> ordered = kinds.isEmpty() ? EnumSet.noneOf(KycFileKind.class) : EnumSet.copyOf(kinds);
        return ordered.stream().map(KycFileKind::code).toList();
    }
}
