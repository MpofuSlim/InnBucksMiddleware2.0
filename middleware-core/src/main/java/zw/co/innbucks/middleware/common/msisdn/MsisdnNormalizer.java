package zw.co.innbucks.middleware.common.msisdn;

import zw.co.innbucks.middleware.common.country.Country;

public interface MsisdnNormalizer {

    Country country();

    String normalize(String input);

    boolean isValid(String input);
}
