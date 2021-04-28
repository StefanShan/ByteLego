package com.byte_stefan.bytelego;

public class ByteLegoInsertCode {

    public static long startTime = 0;

    public static void onMethodEnter(int configIndex, String matchClassName, String matchMethodName, String matchMethodAnnotation) throws Exception {
        if (configIndex == 0){
            if (AnitShakeUtil.isFastDoubleClick()){
                throw new Exception("FastClickException");
            }
        }
    }

    public static void onMethodEnd(int configIndex, String matchClassName, String matchMethodName, String matchMethodAnnotation){
        System.out.println("END...");
    }
}
