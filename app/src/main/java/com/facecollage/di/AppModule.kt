package com.facecollage.di

import android.content.Context
import com.facecollage.ml.CollageRenderer
import com.facecollage.ml.FaceClusterer
import com.facecollage.ml.FaceDetectorWrapper
import com.facecollage.ml.FaceEmbedder
import com.facecollage.ml.VideoFrameExtractor
import com.facecollage.utils.ImageSaver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides @Singleton
    fun provideFaceEmbedder(@ApplicationContext context: Context): FaceEmbedder =
        FaceEmbedder(context)

    @Provides @Singleton
    fun provideFaceDetectorWrapper(embedder: FaceEmbedder): FaceDetectorWrapper =
        FaceDetectorWrapper(embedder)

    @Provides @Singleton
    fun provideFaceClusterer(): FaceClusterer = FaceClusterer()

    @Provides @Singleton
    fun provideVideoFrameExtractor(@ApplicationContext context: Context): VideoFrameExtractor =
        VideoFrameExtractor(context)

    @Provides @Singleton
    fun provideCollageRenderer(): CollageRenderer = CollageRenderer()

    @Provides @Singleton
    fun provideImageSaver(@ApplicationContext context: Context): ImageSaver = ImageSaver(context)
}