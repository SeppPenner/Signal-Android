package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri;
import org.thoughtcrime.securesms.mms.GlideRequest;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideClickListener;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.Locale;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class ThumbnailView extends FrameLayout {

  private static final String TAG = ThumbnailView.class.getSimpleName();

  private ImageView       image;
  private ImageView       playOverlay;
  private int             backgroundColorHint;
  private int             radius;
  private OnClickListener parentClickListener;

  private final int[] dimens = new int[2];
  private final int[] bounds = new int[4];

  private Optional<TransferControlView> transferControls       = Optional.absent();
  private SlideClickListener            thumbnailClickListener = null;
  private SlideClickListener            downloadClickListener  = null;
  private Slide                         slide                  = null;

  public ThumbnailView(Context context) {
    this(context, null);
  }

  public ThumbnailView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ThumbnailView(final Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    inflate(context, R.layout.thumbnail_view, this);

    this.radius      = getResources().getDimensionPixelSize(R.dimen.message_bubble_corner_radius);
    this.image       = findViewById(R.id.thumbnail_image);
    this.playOverlay = findViewById(R.id.play_overlay);
    super.setOnClickListener(new ThumbnailClickDispatcher());

    if (attrs != null) {
      TypedArray typedArray = context.getTheme().obtainStyledAttributes(attrs, R.styleable.ThumbnailView, 0, 0);
      backgroundColorHint   = typedArray.getColor(R.styleable.ThumbnailView_backgroundColorHint, Color.BLACK);
      bounds[0]             = typedArray.getDimensionPixelSize(R.styleable.ThumbnailView_minWidth, 0);
      bounds[1]             = typedArray.getDimensionPixelSize(R.styleable.ThumbnailView_maxWidth, 0);
      bounds[2]             = typedArray.getDimensionPixelSize(R.styleable.ThumbnailView_minHeight, 0);
      bounds[3]             = typedArray.getDimensionPixelSize(R.styleable.ThumbnailView_maxHeight, 0);
      typedArray.recycle();
    }
  }

  @Override
  protected void onMeasure(int originalWidthMeasureSpec, int originalHeightMeasureSpec) {
    Log.e("SPIDERMAN", String.format(Locale.ENGLISH, "dimens: %d x %d   bounds: [ %d, %d, %d, %d ]", dimens[0], dimens[1], bounds[0], bounds[1], bounds[2], bounds[2], bounds[3]));

    Pair<Integer, Integer> targetDimens = getTargetDimensions(dimens, bounds);
    if (targetDimens.first == 0 && targetDimens.second == 0) {
      super.onMeasure(originalWidthMeasureSpec, originalHeightMeasureSpec);
      return;
    }

    int minWidth  = bounds[0];
    int minHeight = bounds[2];

    int finalWidth  = Math.max(targetDimens.first, minWidth) + getPaddingLeft() + getPaddingRight();
    int finalHeight = Math.max(targetDimens.second, minHeight) + getPaddingTop() + getPaddingBottom();

    int newWidthMeasureSpec  = MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.getMode(originalWidthMeasureSpec));
    int newHeightMeasureSpec = MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.getMode(originalHeightMeasureSpec));

    super.onMeasure(newWidthMeasureSpec, newHeightMeasureSpec);
  }

  @SuppressWarnings("SuspiciousNameCombination")
  private Pair<Integer, Integer> getTargetDimensions(int[] dimens, int[] bounds) {
    int dimensFilledCount = getNonZeroCount(dimens);
    int boundsFilledCount = getNonZeroCount(bounds);

    if (dimensFilledCount == 0 || boundsFilledCount == 0) {
      return new Pair<>(0, 0);
    }

    double naturalWidth  = dimens[0];
    double naturalHeight = dimens[1];

    int minWidth  = bounds[0];
    int maxWidth  = bounds[1];
    int minHeight = bounds[2];
    int maxHeight = bounds[3];

    if (dimensFilledCount > 0 && dimensFilledCount < dimens.length) {
      throw new IllegalStateException(String.format(Locale.ENGLISH, "Width or height has been specified, but not both. Dimens: %d x %d",
          naturalWidth, naturalHeight));
    }
    if (boundsFilledCount > 0 && boundsFilledCount < bounds.length) {
      throw new IllegalStateException(String.format(Locale.ENGLISH, "One or more min/max dimensions have been specified, but not all. Bounds: [%d, %d, %d, %d]",
          minWidth, maxWidth, minHeight, maxHeight));
    }

    double measuredWidth  = naturalWidth;
    double measuredHeight = naturalHeight;

    boolean widthInBounds  = measuredWidth >= minWidth && measuredWidth <= maxWidth;
    boolean heightInBounds = measuredHeight >= minHeight && measuredHeight <= maxHeight;

    if (!widthInBounds || !heightInBounds) {
      double minWidthRatio  = naturalWidth / minWidth;
      double maxWidthRatio  = naturalWidth / maxWidth;
      double minHeightRatio = naturalHeight / minHeight;
      double maxHeightRatio = naturalHeight / maxHeight;

      if (maxWidthRatio > 1 || maxHeightRatio > 1) {
        if (maxWidthRatio >= maxHeightRatio) {
          measuredWidth  /= maxWidthRatio;
          measuredHeight /= maxWidthRatio;
        } else {
          measuredWidth  /= maxHeightRatio;
          measuredHeight /= maxHeightRatio;
        }
      } else if (minWidthRatio < 1 || minHeightRatio < 1) {
        if (minWidthRatio <= minHeightRatio) {
          measuredWidth  /= minWidthRatio;
          measuredHeight /= minWidthRatio;
        } else {
          measuredWidth  /= minHeightRatio;
          measuredHeight /= minHeightRatio;
        }
      }
    }

    return new Pair<>((int) measuredWidth, (int) measuredHeight);
  }

  private int getNonZeroCount(int[] vals) {
    int count = 0;
    for (int val : vals) {
      if (val > 0) {
        count++;
      }
    }
    return count;
  }

  @Override
  public void setOnClickListener(OnClickListener l) {
    parentClickListener = l;
  }

  @Override
  public void setFocusable(boolean focusable) {
    super.setFocusable(focusable);
    if (transferControls.isPresent()) transferControls.get().setFocusable(focusable);
  }

  @Override
  public void setClickable(boolean clickable) {
    super.setClickable(clickable);
    if (transferControls.isPresent()) transferControls.get().setClickable(clickable);
  }

  private TransferControlView getTransferControls() {
    if (!transferControls.isPresent()) {
      transferControls = Optional.of(ViewUtil.inflateStub(this, R.id.transfer_controls_stub));
    }
    return transferControls.get();
  }

  public void setBackgroundColorHint(int color) {
    this.backgroundColorHint = color;
  }

  public void setImageResource(@NonNull GlideRequests glideRequests, @NonNull Slide slide,
                               boolean showControls, boolean isPreview) {
    setImageResource(glideRequests, slide, showControls, isPreview, 0, 0);
  }

  public void setImageResource(@NonNull GlideRequests glideRequests, @NonNull Slide slide,
                               boolean showControls, boolean isPreview, int naturalWidth,
                               int naturalHeight)
  {
    dimens[0] = naturalWidth;
    dimens[1] = naturalHeight;

    if (showControls) {
      getTransferControls().setSlide(slide);
      getTransferControls().setDownloadClickListener(new DownloadClickDispatcher());
    } else if (transferControls.isPresent()) {
      getTransferControls().setVisibility(View.GONE);
    }

    if (slide.getThumbnailUri() != null && slide.hasPlayOverlay() &&
        (slide.getTransferState() == AttachmentDatabase.TRANSFER_PROGRESS_DONE || isPreview))
    {
      this.playOverlay.setVisibility(View.VISIBLE);
    } else {
      this.playOverlay.setVisibility(View.GONE);
    }

    if (Util.equals(slide, this.slide)) {
      Log.w(TAG, "Not re-loading slide " + slide.asAttachment().getDataUri());
      return;
    }

    if (this.slide != null && this.slide.getFastPreflightId() != null &&
        this.slide.getFastPreflightId().equals(slide.getFastPreflightId()))
    {
      Log.w(TAG, "Not re-loading slide for fast preflight: " + slide.getFastPreflightId());
      this.slide = slide;
      return;
    }

    Log.w(TAG, "loading part with id " + slide.asAttachment().getDataUri()
               + ", progress " + slide.getTransferState() + ", fast preflight id: " +
               slide.asAttachment().getFastPreflightId());

    this.slide = slide;

    if      (slide.getThumbnailUri() != null) buildThumbnailGlideRequest(glideRequests, slide).into(image);
    else if (slide.hasPlaceholder())          buildPlaceholderGlideRequest(glideRequests, slide).into(image);
    else                                      glideRequests.clear(image);

  }

  public void setImageResource(@NonNull GlideRequests glideRequests, @NonNull Uri uri) {
    // TODO: REMOVE
    if (transferControls.isPresent()) getTransferControls().setVisibility(View.GONE);
    GlideRequest request = glideRequests.load(new DecryptableUri(uri))
                 .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                 .transform(new RoundedCorners(radius))
                 .transition(withCrossFade());

    Pair<Integer, Integer> targetDimens = getTargetDimensions(dimens, bounds);
    if (targetDimens.first == 0 && targetDimens.second == 0) {
      request = request.centerCrop();
    } else {
      request = request.override(targetDimens.first, targetDimens.second);
    }
    request.into(image);
  }

  public void setThumbnailClickListener(SlideClickListener listener) {
    this.thumbnailClickListener = listener;
  }

  public void setDownloadClickListener(SlideClickListener listener) {
    this.downloadClickListener = listener;
  }

  public void clear(GlideRequests glideRequests) {
    glideRequests.clear(image);

    if (transferControls.isPresent()) {
      getTransferControls().clear();
    }

    slide = null;
  }

  public void showProgressSpinner() {
    getTransferControls().showProgressSpinner();
  }

  private GlideRequest buildThumbnailGlideRequest(@NonNull GlideRequests glideRequests, @NonNull Slide slide) {
    GlideRequest builder = glideRequests.load(new DecryptableUri(slide.getThumbnailUri()))
                                          .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                          .transform(new RoundedCorners(radius))
                                          .transition(withCrossFade());

    Pair<Integer, Integer> targetDimens = getTargetDimensions(dimens, bounds);
    if (targetDimens.first == 0 && targetDimens.second == 0) {
      builder = builder.centerCrop();
    } else {
      builder = builder.override(targetDimens.first, targetDimens.second);
    }
    if (slide.isInProgress()) return builder;
    else                      return builder.apply(RequestOptions.errorOf(R.drawable.ic_missing_thumbnail_picture));
  }

  private RequestBuilder buildPlaceholderGlideRequest(@NonNull GlideRequests glideRequests, @NonNull Slide slide) {
    return glideRequests.asBitmap()
                        .load(slide.getPlaceholderRes(getContext().getTheme()))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .fitCenter();
  }

  private class ThumbnailClickDispatcher implements View.OnClickListener {
    @Override
    public void onClick(View view) {
      if (thumbnailClickListener            != null &&
          slide                             != null &&
          slide.asAttachment().getDataUri() != null &&
          slide.getTransferState()          == AttachmentDatabase.TRANSFER_PROGRESS_DONE)
      {
        thumbnailClickListener.onClick(view, slide);
      } else if (parentClickListener != null) {
        parentClickListener.onClick(view);
      }
    }
  }

  private class DownloadClickDispatcher implements View.OnClickListener {
    @Override
    public void onClick(View view) {
      if (downloadClickListener != null && slide != null) {
        downloadClickListener.onClick(view, slide);
      }
    }
  }
}
