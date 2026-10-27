package io.github.fijeastudio.artellis;

/**
 * A wall's theme: one place and period. The Art Institute is asked by place_of_origin and date_start; The Met
 * (European Paintings only) by a word in its search and the artist's nationality, with dateBegin/dateEnd.
 * Never shown on screen: the labels speak for themselves.
 */
final class Theme {

    final String aicPlace;
    /** Null: The Met has no matching paintings in its European department (American art, say). */
    final String metNationality;
    final int from;
    final int to;

    private Theme(String aicPlace, String metNationality, int from, int to) {
        this.aicPlace = aicPlace;
        this.metNationality = metNationality;
        this.from = from;
        this.to = to;
    }

    static final Theme[] ALL = {
            new Theme("Italy", "Italian", 1300, 1550),         // Renaissance
            new Theme("Italy", "Italian", 1550, 1780),         // Baroque to Venice's vedute
            new Theme("Netherlands", "Dutch", 1600, 1700),     // Golden Age
            new Theme("Flanders", "Flemish", 1430, 1700),
            new Theme("Spain", "Spanish", 1500, 1850),
            new Theme("France", "French", 1650, 1800),
            new Theme("France", "French", 1800, 1860),
            new Theme("France", "French", 1860, 1910),         // Impressionism and after
            new Theme("England", "British", 1700, 1900),
            new Theme("Germany", "German", 1430, 1900),
            new Theme("United States", null, 1800, 1925),
            new Theme("Netherlands", "Dutch", 1800, 1910),     // The Hague School to Van Gogh
    };
}
