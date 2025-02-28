//
//  VideoRenderer.h
//  tsr-ios-demo
//
//  Created by Junfeng Gao on 2025/2/25.
//

#ifndef VideoRenderer_h
#define VideoRenderer_h

#import <Foundation/Foundation.h>
#import <GLKit/GLKit.h>
#import <AVFoundation/AVFoundation.h>

@interface VideoRenderer : NSObject

@property (nonatomic, strong) EAGLContext *glContext;
@property (nonatomic, assign) int inputWidth;
@property (nonatomic, assign) int inputHeight;


- (instancetype)initWithContext:(EAGLContext *)context inputWidth: (int)inputWidth inputHeight: (int)inputHeight;
- (void)setupGL;
- (void)render:(GLuint)texture;
- (void)cleanupGL;

@end


#endif /* VideoRenderer_h */
