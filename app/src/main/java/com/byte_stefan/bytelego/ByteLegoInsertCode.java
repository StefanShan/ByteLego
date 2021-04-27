package com.byte_stefan.bytelego;

public class ByteLegoInsertCode {

    public static long startTime = 0;

    public static void onMethodEnter(int configIndex){
        if (configIndex == 0){
            AnitShakeUtil.isFastDoubleClick();
        }
    }
}
