package org.debian.decopy.datatypes;

/**
 * A year range with low and high bounds.
 */
public final class YearRange {

    private int low;
    private int high;

    public YearRange() {
        this.low = 0;
        this.high = 0;
    }

    public YearRange(int low, int high) {
        if (low > high) {
            this.low = high;
            this.high = low;
        } else {
            this.low = low;
            this.high = high;
        }
    }

    public int getLow() { return low; }
    public int getHigh() { return high; }

    public boolean contains(int year) {
        return low <= year && year <= high;
    }

    public YearRange add(int year) {
        if (year == 0) return this;
        if (low == 0 || year < low) low = year;
        if (high == 0 || year > high) high = year;
        return this;
    }

    public boolean newer(YearRange other) {
        if (high != 0 && other.high != 0) return other.high > high;
        return high == 0 && other.high != 0;
    }

    public YearRange merge(YearRange other) {
        if (other.low != 0) add(other.low);
        if (other.high != 0) add(other.high);
        return this;
    }

    public boolean isEmpty() {
        return low == 0;
    }

    @Override
    public String toString() {
        if (low == 0) return "";
        if (low == high) return String.valueOf(low);
        return low + "-" + high;
    }
}
