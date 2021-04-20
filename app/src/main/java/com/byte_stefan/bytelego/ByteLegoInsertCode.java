package com.byte_stefan.bytelego;

public class ByteLegoInsertCode {

    public static long startTime = 0;

    public static void onMethodEnter(int configIndex){
        if (configIndex == 0){
            startTime = System.currentTimeMillis();
        }else if (configIndex == 2){
            AnitShakeUtil.isFastDoubleClick();
        }
    }

    public static void onMethodExit(int configIndex){
        if (configIndex == 0){
            System.out.println("create方法耗时 = " + (System.currentTimeMillis() - startTime));
        }else if (configIndex == 1){
            System.out.println("activity方法耗时 = " + (System.currentTimeMillis() - startTime));
        }
    }
}
