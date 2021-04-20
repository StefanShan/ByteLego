package com.byte_stefan.bytelego;

public class AnitShakeUtil {
    private static final int DEFAULT_DISTANCE_TIME = 500;
    private static long mLastClickTime = 0L;

    public static boolean isFastDoubleClick() {
        long time = System.currentTimeMillis();
        if (time - mLastClickTime < DEFAULT_DISTANCE_TIME) {
            mLastClickTime = time;
            return true;
        } else {
            mLastClickTime = time;
            return false;
        }
    }

}
