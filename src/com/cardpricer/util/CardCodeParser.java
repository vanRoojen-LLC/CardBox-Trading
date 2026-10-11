package com.cardpricer.util;

import com.cardpricer.model.ParsedCode;
import java.util.Locale;
import java.util.regex.Pattern;

/** Static utility for parsing and formatting user-typed card codes. */
public final class CardCodeParser {
    private static final Pattern CODE = Pattern.compile(
            "(?i)^([a-z0-9]{2,8}) (?:([a-z0-9]{2,8})[ -])?(\\d+[a-z0-9★†.*-]*)(?: ([fes]))?$");

    private CardCodeParser() {}

    /**
     * Parses input like "TDM 3f", "PEOE 210sf", or "PIP 882s" into a ParsedCode.
     * An explicitly separated finish preserves every character of the collector number.
     * Returns {@code null} if the input cannot be parsed.
     */
    public static ParsedCode parse(String input) {
        if (input==null || input.isBlank()) return null;
        String text=input.trim().replaceAll("\\s+", " ");
        text=text.replaceFirst("(?i)^([a-z]{3,8})(?=\\d)", "$1 ");
        var match=CODE.matcher(text);
        if (!match.matches()) return null;
        String set=match.group(1), prefix=match.group(2), number=match.group(3), finish=match.group(4);
        if (prefix!=null && !"plst".equalsIgnoreCase(set)) return null;
        if (finish==null) {
            finish="";
            // In set promos, a bare numeric s suffix belongs to the prerelease
            // collector number. In 210sf only the final f selects the finish.
            boolean prereleaseNumber=isPromoSet(prefix==null ? set : prefix) && number.matches("(?i)\\d+s");
            if (!prereleaseNumber && number.matches("(?i).+[fes]$")) {
                finish=number.substring(number.length()-1);
                number=number.substring(0,number.length()-1);
            }
        }
        number=number.toLowerCase(Locale.ROOT);
        if (prefix!=null) number=prefix.toUpperCase(Locale.ROOT)+"-"+number;
        return forLookup(set,number,finish);
    }

    /** Builds a lookup from separate fields, preserving markers and the known 40K surge numbering. */
    public static ParsedCode forLookup(String set,String number,String finish) {
        String providerSet=SetList.toScryfallCode(set);
        String selectedFinish=finish.toUpperCase(Locale.ROOT);
        // 40K's surge cards have their own star-numbered records (129 -> 129★).
        // Other sets such as PIP use plain numbers for their surge printings.
        if ("40k".equals(providerSet) && "S".equals(selectedFinish) && number.matches("\\d+")) number+="★";
        return new ParsedCode(providerSet,number,selectedFinish);
    }

    private static boolean isPromoSet(String set) {
        String normalized=SetList.fromScryfallCode(set);
        return normalized.startsWith("P") && !SetList.isValidSetCode(normalized)
                && SetList.isValidSetCode(SetList.fromScryfallCode(normalized.substring(1)));
    }

    /** Formats a ParsedCode without changing its collector number or finish on the next parse. */
    public static String format(ParsedCode code) {
        StringBuilder formatted = new StringBuilder(code.setCode.toUpperCase(Locale.ROOT))
                .append(' ').append(code.collectorNumber);

        if (!code.finish.isEmpty()) {
            // Keep ordinary 3f/3e/3s codes compact; separate marked numbers and
            // promo surge requests so Enter cannot reinterpret part of the code.
            if (!code.collectorNumber.matches("\\d+") || ("S".equalsIgnoreCase(code.finish) && isPromoSet(code.setCode)))
                formatted.append(' ');
            formatted.append(code.finish.toLowerCase(Locale.ROOT));
        }

        return formatted.toString();
    }

    /** Capitalizes the first letter of a string. */
    public static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
