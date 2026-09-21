package dk.lasse.karateanalyzer.geometry

/**
 * Coordinate transformer that maps coordinates across:
 * - Canonical source-normalized frame space
 * - Crop-local normalized space
 * - Displayed-image canvas space
 *
 * Implements Fit/Crop scaling, alignment, zoom/pan, unclamped mathematical inversion,
 * and separate hit-testing.
 */
object OverlayCoordinateTransformer {

    fun sourceToCrop(
        point: SourceNormalizedPoint,
        crop: NormalizedCrop,
    ): CropLocalPoint {
        return CropLocalPoint(
            x = (point.x - crop.left) / crop.width,
            y = (point.y - crop.top) / crop.height,
        )
    }

    fun cropToSource(
        point: CropLocalPoint,
        crop: NormalizedCrop,
    ): SourceNormalizedPoint {
        return SourceNormalizedPoint(
            x = crop.left + point.x * crop.width,
            y = crop.top + point.y * crop.height,
        )
    }

    fun sourceToCrop(
        point: SourceNormalizedPoint,
        crop: AppliedPixelCrop,
        frameGeometry: FrameGeometry,
    ): CropLocalPoint {
        val srcPxX = point.x * frameGeometry.sourceWidth
        val srcPxY = point.y * frameGeometry.sourceHeight
        return CropLocalPoint(
            x = (srcPxX - crop.left) / crop.width.toFloat(),
            y = (srcPxY - crop.top) / crop.height.toFloat(),
        )
    }

    fun cropToSource(
        point: CropLocalPoint,
        crop: AppliedPixelCrop,
        frameGeometry: FrameGeometry,
    ): SourceNormalizedPoint {
        val srcPxX = crop.left + point.x * crop.width.toFloat()
        val srcPxY = crop.top + point.y * crop.height.toFloat()
        return SourceNormalizedPoint(
            x = srcPxX / frameGeometry.sourceWidth.toFloat(),
            y = srcPxY / frameGeometry.sourceHeight.toFloat(),
        )
    }

    /**
     * Resolves the display transform for a given viewport and configuration.
     * Content scaling (FIT / CROP) and alignment apply to the selected image region.
     */
    fun resolveDisplayTransform(
        viewportWidth: Float,
        viewportHeight: Float,
        contentScale: ContentScaleMode,
        alignment: Alignment = Alignment.CENTER,
        zoomPan: ZoomPan = ZoomPan(),
        frameGeometry: FrameGeometry,
        appliedCrop: NormalizedCrop? = null,
    ): ResolvedDisplayTransform {
        require(viewportWidth > 0f && viewportHeight > 0f) {
            "Viewport dimensions must be strictly positive: width=$viewportWidth, height=$viewportHeight"
        }

        val selectedCrop = appliedCrop ?: NormalizedCrop.FULL
        val pixelCrop = selectedCrop.toAppliedPixelCrop(frameGeometry)
        val selectedAspect = pixelCrop.aspectRatio
        val viewportAspect = viewportWidth / viewportHeight

        val baseWidth: Float
        val baseHeight: Float

        when (contentScale) {
            ContentScaleMode.FIT -> {
                if (viewportAspect > selectedAspect) {
                    baseHeight = viewportHeight
                    baseWidth = baseHeight * selectedAspect
                } else {
                    baseWidth = viewportWidth
                    baseHeight = baseWidth / selectedAspect
                }
            }
            ContentScaleMode.CROP -> {
                if (viewportAspect > selectedAspect) {
                    baseWidth = viewportWidth
                    baseHeight = baseWidth / selectedAspect
                } else {
                    baseHeight = viewportHeight
                    baseWidth = baseHeight * selectedAspect
                }
            }
        }

        val displayWidth = baseWidth * zoomPan.zoom
        val displayHeight = baseHeight * zoomPan.zoom

        val remX = viewportWidth - displayWidth
        val remY = viewportHeight - displayHeight

        val displayLeft = (remX * 0.5f) + (alignment.horizontalBias * remX * 0.5f) + zoomPan.panX
        val displayTop = (remY * 0.5f) + (alignment.verticalBias * remY * 0.5f) + zoomPan.panY

        val displayedImageBounds = CanvasRect(
            left = displayLeft,
            top = displayTop,
            right = displayLeft + displayWidth,
            bottom = displayTop + displayHeight,
        )

        val viewportBounds = CanvasRect(
            left = 0f,
            top = 0f,
            right = viewportWidth,
            bottom = viewportHeight,
        )

        val contentClippingBounds = displayedImageBounds.intersect(viewportBounds)

        return ResolvedDisplayTransform(
            viewportBounds = viewportBounds,
            displayedImageBounds = displayedImageBounds,
            contentClippingBounds = contentClippingBounds,
            scaleX = displayWidth / pixelCrop.width.toFloat(),
            scaleY = displayHeight / pixelCrop.height.toFloat(),
            selectedCrop = selectedCrop,
            appliedPixelCrop = pixelCrop,
            contentScaleMode = contentScale,
            alignment = alignment,
            zoomPan = zoomPan,
            frameGeometry = frameGeometry,
        )
    }

    /**
     * Maps a canonical source-normalized point to canvas pixel coordinates.
     * Unclamped: points outside the image frame or crop bounds map outside displayed bounds.
     */
    fun sourceToCanvas(
        point: SourceNormalizedPoint,
        transform: ResolvedDisplayTransform,
    ): CanvasPoint {
        require(point.x.isFinite() && point.y.isFinite()) { "Point coordinates must be finite: $point" }
        val authoritativeCrop = transform.appliedPixelCrop.toNormalizedCrop(transform.frameGeometry)
        val cropLocal = sourceToCrop(point, authoritativeCrop)
        val xCanvas = transform.displayedImageBounds.left + cropLocal.x * transform.displayedImageBounds.width
        val yCanvas = transform.displayedImageBounds.top + cropLocal.y * transform.displayedImageBounds.height
        return CanvasPoint(xCanvas, yCanvas)
    }

    /**
     * Maps a canvas pixel coordinate back to canonical source-normalized coordinates.
     * Unclamped: mathematical inverse transform using the authoritative applied pixel crop.
     */
    fun canvasToSource(
        point: CanvasPoint,
        transform: ResolvedDisplayTransform,
    ): SourceNormalizedPoint {
        require(point.x.isFinite() && point.y.isFinite()) { "Point coordinates must be finite: $point" }
        require(transform.displayedImageBounds.width > 0f && transform.displayedImageBounds.height > 0f) {
            "Displayed image bounds must have non-zero dimensions"
        }
        val cropLocalX = (point.x - transform.displayedImageBounds.left) / transform.displayedImageBounds.width
        val cropLocalY = (point.y - transform.displayedImageBounds.top) / transform.displayedImageBounds.height
        val authoritativeCrop = transform.appliedPixelCrop.toNormalizedCrop(transform.frameGeometry)
        return cropToSource(CropLocalPoint(cropLocalX, cropLocalY), authoritativeCrop)
    }

    /**
     * Hit-tests a point in canvas space against the viewport and displayed image.
     */
    fun hitTestCanvasPoint(
        point: CanvasPoint,
        transform: ResolvedDisplayTransform,
    ): HitTestResult {
        require(point.x.isFinite() && point.y.isFinite()) { "Point coordinates must be finite: $point" }
        if (!transform.viewportBounds.contains(point)) {
            return HitTestResult.OUTSIDE_VIEWPORT
        }
        if (transform.displayedImageBounds.contains(point)) {
            return HitTestResult.INSIDE_IMAGE
        }
        return HitTestResult.OUTSIDE_IMAGE
    }
}
