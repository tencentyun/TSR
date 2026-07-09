//
//  VideoColorSpace.m
//  tsr-ios-demo
//
//  从 CMFormatDescription 扩展属性读取 YCbCrMatrix，映射为 VMRYuvColorSpace。
//

#import "VideoColorSpace.h"
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>

@implementation VideoColorSpace

+ (VMRYuvColorSpace)detectFromTrack:(AVAssetTrack *)track {
    // 取第一条 format description（视频轨道通常只有一条）
    NSArray *descs = track.formatDescriptions;
    if (descs.count == 0) return VMRYuvColorSpaceBT709Limited; // 默认 709 Limited

    CMFormatDescriptionRef desc = (__bridge CMFormatDescriptionRef)descs.firstObject;
    CFDictionaryRef exts = CMFormatDescriptionGetExtensions(desc);
    if (!exts) return VMRYuvColorSpaceBT709Limited;

    NSDictionary *ext = (__bridge NSDictionary *)exts;

    // ── YCbCr Matrix ──
    NSString *matrix = ext[(__bridge NSString *)kCVImageBufferYCbCrMatrixKey];
    BOOL isBt709 = [self _isBt709Matrix:matrix]; // BT.2020 → 709 fallback

    // ── Full Range ──
    NSNumber *fullRangeNum = ext[(__bridge NSString *)kCMFormatDescriptionExtension_FullRangeVideo];
    BOOL isFull = fullRangeNum.boolValue;

    if (isBt709) {
        return isFull ? VMRYuvColorSpaceBT709Full : VMRYuvColorSpaceBT709Limited;
    } else {
        return isFull ? VMRYuvColorSpaceBT601Full : VMRYuvColorSpaceBT601Limited;
    }
}

+ (BOOL)_isBt709Matrix:(nullable NSString *)matrix {
    if (!matrix) return YES; // 无合法值 → 默认 BT.709（H.264/H.265 SDR 规范）

    if ([matrix isEqualToString:(__bridge NSString *)kCVImageBufferYCbCrMatrix_ITU_R_709_2] ||
        [matrix isEqualToString:(__bridge NSString *)kCVImageBufferYCbCrMatrix_ITU_R_2020] /* BT.2020 → 709 fallback */) {
        return YES;
    }
    if ([matrix isEqualToString:(__bridge NSString *)kCVImageBufferYCbCrMatrix_ITU_R_601_4] ||
        [matrix isEqualToString:(__bridge NSString *)kCVImageBufferYCbCrMatrix_SMPTE_240M_1995]) {
        return NO;
    }
    return YES; // 未知 → 709
}

+ (NSString *)debugString:(VMRYuvColorSpace)cs {
    switch (cs) {
        case VMRYuvColorSpaceBT601Limited: return @"BT.601 Limited";
        case VMRYuvColorSpaceBT601Full:    return @"BT.601 Full";
        case VMRYuvColorSpaceBT709Limited: return @"BT.709 Limited";
        case VMRYuvColorSpaceBT709Full:    return @"BT.709 Full";
    }
}

@end
