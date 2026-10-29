package zw.co.innbucks.middleware.kyc.nationalid;

import zw.co.innbucks.middleware.common.country.Country;

import java.util.Set;

/**
 * Per-country national-ID rules, shaped like {@code MsisdnNormalizer}: one
 * implementation per market, picked by {@link NationalIdValidatorRegistry}
 * from {@code INNBUCKS_COUNTRY}.
 *
 * <p>Two jobs. {@link #canonicalise} turns what a customer typed into the ONE
 * form every hash, comparison and duplicate search uses, or refuses it.
 * {@link #commonSpellings} goes the other way: the sign-up hash was taken over
 * the number EXACTLY as typed (trimmed only), so matching a canonical number
 * against it means trying the ways people actually write it.
 *
 * <p>Validation is fail-closed on characters (anything but letters, digits,
 * spaces and dashes refuses, like the MSISDN normalizers) but deliberately
 * loose on structure where the official rules are not documented: a refusal
 * locks a real customer out of KYC with no way round it, while a wrong-but-
 * well-formed number still goes in front of a reviewer holding the ID photo.
 */
public interface NationalIdValidator {

    Country country();

    /**
     * The canonical form (upper-case ASCII letters and digits, no separators).
     *
     * @throws InvalidNationalIdException if the input is not a well-formed
     *         number for this country; the message never contains the input
     */
    String canonicalise(String input);

    /**
     * The spellings a person plausibly typed for {@code canonical}, the
     * canonical form included. Used ONLY to match legacy, as-typed hashes; it
     * is fine for this to be generous, since every candidate is compared
     * against one customer's (or one ID's) hash, never offered to anyone.
     */
    Set<String> commonSpellings(String canonical);
}
