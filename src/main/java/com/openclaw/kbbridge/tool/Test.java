package com.openclaw.kbbridge.tool;

import java.util.HashSet;

public class Test {
    static void main() {
        System.out.println(fun("abcabc"));
    }

    public static Integer fun(String s) {
        HashSet<String> hashSet = new HashSet<>();
        int num = 0;
        int max = 0;
        for (int j = 0; j < s.length() - 1; j++) {
            for (int i = j; i < s.length(); i++) {
                num = hashSet.size();
                hashSet.add(String.valueOf(s.charAt(i)));
                if (num == hashSet.size() - 1) {
                    num++;
                } else {
                    if (num > max) {
                        max = num;
                    }
                    num = 0;
                    hashSet.clear();
                    break;
                }
            }
        }
        return max;
    }
}
