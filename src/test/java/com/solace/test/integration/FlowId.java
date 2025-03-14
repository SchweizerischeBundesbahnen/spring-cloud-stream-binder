package com.solace.test.integration;

import java.util.BitSet;

public class FlowId {
    public static void main(String[] args) {
        long l = 2097153;
        System.out.println(l & ~(1L << 21));
        System.out.println(l & 0x1FFFFF);
        System.out.println(l & ((1<<(21))-1));
    }
}
