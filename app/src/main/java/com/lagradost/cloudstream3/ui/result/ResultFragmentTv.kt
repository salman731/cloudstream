package com.lagradost.cloudstream3.ui.result

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.APIHolder.updateHasTrailers
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.FragmentResultTvBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup
import com.lagradost.cloudstream3.ui.player.ExtractorLinkGenerator
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.ui.result.ResultFragment.updateUIEvent
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.utils.AppUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppUtils.html
import com.lagradost.cloudstream3.utils.AppUtils.loadCache
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogInstant
import com.lagradost.cloudstream3.utils.UIHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.popCurrentPage
import com.lagradost.cloudstream3.utils.UIHelper.setImage
import com.lagradost.cloudstream3.utils.UIHelper.setImageBlur
import kotlinx.coroutines.delay

class ResultFragmentTv : Fragment() {
    protected lateinit var viewModel: ResultViewModel2
    private var binding: FragmentResultTvBinding? = null

    override fun onDestroyView() {
        binding = null
        updateUIEvent -= ::updateUI
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel =
            ViewModelProvider(this)[ResultViewModel2::class.java]
        updateUIEvent += ::updateUI

        val localBinding = FragmentResultTvBinding.inflate(inflater, container, false)
        binding = localBinding
        return localBinding.root
    }

    private fun updateUI(id: Int?) {
        viewModel.reloadEpisodes()
    }

    private var currentRecommendations: List<SearchResponse> = emptyList()

    private fun handleSelection(data: Any) {
        when (data) {
            is EpisodeRange -> {
                viewModel.changeRange(data)
            }

            is Int -> {
                viewModel.changeSeason(data)
            }

            is DubStatus -> {
                viewModel.changeDubStatus(data)
            }

            is String -> {
                setRecommendations(currentRecommendations, data)
            }
        }
    }

    private fun RecyclerView?.select(index: Int) {
        (this?.adapter as? SelectAdaptor?)?.select(index, this)
    }

    private fun RecyclerView?.update(data: List<SelectData>) {
        (this?.adapter as? SelectAdaptor?)?.updateSelectionList(data)
        this?.isVisible = data.size > 1
    }

    private fun RecyclerView?.setAdapter() {
        this?.adapter = SelectAdaptor { data ->
            handleSelection(data)
        }
    }

    private fun hasNoFocus(): Boolean {
        val focus = activity?.currentFocus
        if (focus == null || !focus.isVisible) return true
        return focus == binding?.resultRoot
    }

    private fun setRecommendations(rec: List<SearchResponse>?, validApiName: String?) {
        currentRecommendations = rec ?: emptyList()
        val isInvalid = rec.isNullOrEmpty()
        binding?.apply {
            resultRecommendationsList.isGone = isInvalid
            resultRecommendationsHolder.isGone = isInvalid
            val matchAgainst = validApiName ?: rec?.firstOrNull()?.apiName
            (resultRecommendationsList.adapter as? SearchAdapter)?.updateList(rec?.filter { it.apiName == matchAgainst }
                ?: emptyList())

            rec?.map { it.apiName }?.distinct()?.let { apiNames ->
                // very dirty selection
                resultRecommendationsFilterSelection.isVisible = apiNames.size > 1
                resultRecommendationsFilterSelection.update(apiNames.map { txt(it) to it })
                resultRecommendationsFilterSelection.select(apiNames.indexOf(matchAgainst))
            } ?: run {
                resultRecommendationsFilterSelection.isVisible = false
            }
        }
    }

    var loadingDialog: Dialog? = null
    var popupDialog: Dialog? = null

    private fun reloadViewModel(forceReload: Boolean) {
        if (!viewModel.hasLoaded() || forceReload) {
            val storedData = getStoredData() ?: return
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        }
    }

    override fun onResume() {
        activity?.let {
            it.window?.navigationBarColor =
                it.colorFromAttribute(R.attr.primaryBlackBackground)
        }
        afterPluginsLoadedEvent += ::reloadViewModel
        super.onResume()
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::reloadViewModel
        super.onStop()
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ===== setup =====
        val storedData = getStoredData() ?: return
        activity?.window?.decorView?.clearFocus()
        activity?.loadCache()
        hideKeyboard()
        if (storedData.restart || !viewModel.hasLoaded())
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        // ===== ===== =====

        binding?.apply {
            resultEpisodes.layoutManager =
                LinearListLayout(resultEpisodes.context).apply {
                    setVertical()
                }

            resultReloadConnectionerror.setOnClickListener {
                viewModel.load(
                    activity,
                    storedData.url,
                    storedData.apiName,
                    storedData.showFillers,
                    storedData.dubStatus,
                    storedData.start
                )

            }

            resultMetaSite.isFocusable = false

            //resultReloadConnectionOpenInBrowser.setOnClickListener {view ->
            //    view.context?.openBrowser(storedData?.url ?: return@setOnClickListener, fallbackWebview = true)
            //}

            resultSeasonSelection.setAdapter()
            resultRangeSelection.setAdapter()
            resultDubSelection.setAdapter()
            resultRecommendationsFilterSelection.setAdapter()

            resultCastItems.setOnFocusChangeListener { _, hasFocus ->
                // Always escape focus
                if (hasFocus) binding?.resultBookmarkButton?.requestFocus()
            }
            //resultBack.setOnClickListener {
            //    activity?.popCurrentPage()
            //}

            resultRecommendationsList.spanCount = 8
            resultRecommendationsList.adapter =
                SearchAdapter(
                    ArrayList(),
                    resultRecommendationsList,
                ) { callback ->
                    SearchHelper.handleSearchClickCallback(callback)
                }

            resultEpisodes.adapter =
                EpisodeAdapter(
                    false,
                    { episodeClick ->
                        viewModel.handleAction(episodeClick)
                    },
                    { downloadClickEvent ->
                        DownloadButtonSetup.handleDownloadClick(downloadClickEvent)
                    }
                )

            resultCastItems.layoutManager = object : LinearListLayout(view.context) {
                override fun onRequestChildFocus(
                    parent: RecyclerView,
                    state: RecyclerView.State,
                    child: View,
                    focused: View?
                ): Boolean {
                    // Make the cast always focus the first visible item when focused
                    // from somewhere else. Otherwise it jumps to the last item.
                    return if (parent.focusedChild == null) {
                        scrollToPosition(this.findFirstCompletelyVisibleItemPosition())
                        true
                    } else {
                        super.onRequestChildFocus(parent, state, child, focused)
                    }
                }
            }.apply {
                this.orientation = RecyclerView.HORIZONTAL
            }
            resultCastItems.adapter = ActorAdaptor()
        }

        observeNullable(viewModel.resumeWatching) { resume ->
            binding?.apply {
                // show progress no matter if series or movie
                resume?.progress?.let { progress ->
                    resultResumeSeriesProgressText.setText(progress.progressLeft)
                    resultResumeSeriesProgress.apply {
                        isVisible = true
                        this.max = progress.maxProgress
                        this.progress = progress.progress
                    }
                    resultResumeProgressHolder.isVisible = true
                } ?: run {
                    resultResumeProgressHolder.isVisible = false
                }

                // if movie then hide both as movie button is
                // always visible on movies, this is done in movie observe

                if(resume?.isMovie == true) {
                    resultPlaySeries.isVisible = false
                    resultResumeSeries.isVisible = false
                    return@observeNullable
                }

                // if series then
                // > resultPlaySeries is visible when null
                // > resultResumeSeries is visible when not null
                if (resume == null) {
                    resultPlaySeries.isVisible = true
                    resultResumeSeries.isVisible = false
                    return@observeNullable
                }

                resultPlaySeries.isVisible = false
                resultResumeSeries.isVisible = true

                if (hasNoFocus()) {
                    resultResumeSeries.requestFocus()
                }

                resultResumeSeries.text =
                    if (resume.isMovie) context?.getString(R.string.play_movie_button) else context?.getNameFull(
                        null, // resume.result.name, we don't want episode title
                        resume.result.episode,
                        resume.result.season
                    )

                resultResumeSeries.setOnClickListener {
                    viewModel.handleAction(
                        EpisodeClickEvent(
                            storedData.playerAction, //?: ACTION_PLAY_EPISODE_IN_PLAYER,
                            resume.result
                        )
                    )
                }

                resultResumeSeries.setOnLongClickListener {
                    viewModel.handleAction(
                        EpisodeClickEvent(ACTION_SHOW_OPTIONS, resume.result)
                    )
                    return@setOnLongClickListener true
                }

            }
        }

        observe(viewModel.trailers) { trailersLinks ->
            context?.updateHasTrailers()
            if (!LoadResponse.isTrailersEnabled) return@observe
            val trailers = trailersLinks.flatMap { it.mirros }
            binding?.resultPlayTrailer?.apply {
                isGone = trailers.isEmpty()
                setOnClickListener {
                    if (trailers.isEmpty()) return@setOnClickListener
                    activity.navigate(
                        R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                            ExtractorLinkGenerator(
                                trailers,
                                emptyList()
                            )
                        )
                    )
                }
            }
        }

        observe(viewModel.watchStatus) { watchType ->
            binding?.resultBookmarkButton?.apply {
                setText(watchType.stringRes)
                setOnClickListener { view ->
                    activity?.showBottomDialog(
                        WatchType.values().map { view.context.getString(it.stringRes) }.toList(),
                        watchType.ordinal,
                        view.context.getString(R.string.action_add_to_bookmarks),
                        showApply = false,
                        {}) {
                        viewModel.updateWatchStatus(WatchType.values()[it])
                    }
                }
            }
        }

        observeNullable(viewModel.movie) { data ->
            binding?.apply {
                resultPlayMovie.isVisible = data is Resource.Success
                seriesHolder.isVisible = data == null

                (data as? Resource.Success)?.value?.let { (text, ep) ->
                    resultPlayMovie.setText(text)
                    resultPlayMovie.setOnClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_CLICK_DEFAULT, ep)
                        )
                    }
                    resultPlayMovie.setOnLongClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_SHOW_OPTIONS, ep)
                        )
                        return@setOnLongClickListener true
                    }
                    if (hasNoFocus()) {
                        resultPlayMovie.requestFocus()
                    }
                }
            }
        }

        observeNullable(viewModel.selectPopup) { popup ->
            if (popup == null) {
                popupDialog?.dismissSafe(activity)
                popupDialog = null
                return@observeNullable
            }

            popupDialog?.dismissSafe(activity)

            popupDialog = activity?.let { act ->
                val options = popup.getOptions(act)
                val title = popup.getTitle(act)

                act.showBottomDialogInstant(
                    options, title, {
                        popupDialog = null
                        popup.callback(null)
                    }, {
                        popupDialog = null
                        popup.callback(it)
                    }
                )
            }
        }

        observeNullable(viewModel.loadedLinks) { load ->
            if (load == null) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
                return@observeNullable
            }
            if (loadingDialog?.isShowing != true) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
            }
            loadingDialog = loadingDialog ?: context?.let { ctx ->
                val builder = BottomSheetDialog(ctx)
                builder.setContentView(R.layout.bottom_loading)
                builder.setOnDismissListener {
                    loadingDialog = null
                    viewModel.cancelLinks()
                }
                //builder.setOnCancelListener {
                //    it?.dismiss()
                //}
                builder.setCanceledOnTouchOutside(true)
                builder.show()
                builder
            }

        }


        observeNullable(viewModel.episodesCountText) { count ->
            binding?.resultEpisodesText.setText(count)
        }

        observe(viewModel.selectedRangeIndex) { selected ->
            binding?.resultRangeSelection.select(selected)
        }
        observe(viewModel.selectedSeasonIndex) { selected ->
            binding?.resultSeasonSelection.select(selected)
        }
        observe(viewModel.selectedDubStatusIndex) { selected ->
            binding?.resultDubSelection.select(selected)
        }
        observe(viewModel.rangeSelections) {
            binding?.resultRangeSelection.update(it)
        }
        observe(viewModel.dubSubSelections) {
            binding?.resultDubSelection.update(it)
        }
        observe(viewModel.seasonSelections) {
            binding?.resultSeasonSelection.update(it)
        }
        observe(viewModel.recommendations) { recommendations ->
            setRecommendations(recommendations, null)
        }
        observe(viewModel.episodeSynopsis) { description ->
            view.context?.let { ctx ->
                val builder: AlertDialog.Builder =
                    AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                builder.setMessage(description.html())
                    .setTitle(R.string.synopsis)
                    .setOnDismissListener {
                        viewModel.releaseEpisodeSynopsis()
                    }
                    .show()
            }
        }
        observeNullable(viewModel.episodes) { episodes ->
            binding?.apply {
                resultEpisodes.isVisible = episodes is Resource.Success
                resultEpisodeLoading.isVisible = episodes is Resource.Loading
                if (episodes is Resource.Success) {
                    val first = episodes.value.firstOrNull()
                    if (first != null) {
                        resultPlaySeries.text = context?.getNameFull(
                            null, // resume.result.name, we don't want episode title
                            first.episode,
                            first.season
                        )

                        resultPlaySeries.setOnClickListener {
                            viewModel.handleAction(
                                EpisodeClickEvent(
                                    ACTION_PLAY_EPISODE_IN_PLAYER,
                                    first
                                )
                            )
                        }
                        resultPlaySeries.setOnLongClickListener {
                            viewModel.handleAction(
                                EpisodeClickEvent(ACTION_SHOW_OPTIONS, first)
                            )
                            return@setOnLongClickListener true
                        }
                    }

                    /*
                     * Okay so what is this fuckery?
                     * Basically Android TV will crash if you request a new focus while
                     * the adapter gets updated.
                     *
                     * This means that if you load thumbnails and request a next focus at the same time
                     * the app will crash without any way to catch it!
                     *
                     * How to bypass this?
                     * This code basically steals the focus for 500ms and puts it in an inescapable view
                     * then lets out the focus by requesting focus to result_episodes
                     */

                    val hasEpisodes =
                        !(resultEpisodes.adapter as? EpisodeAdapter?)?.cardList.isNullOrEmpty()

                    if (hasEpisodes) {
                        // Make it impossible to focus anywhere else!
                        temporaryNoFocus.isFocusable = true
                        temporaryNoFocus.requestFocus()
                    }

                    (resultEpisodes.adapter as? EpisodeAdapter)?.updateList(episodes.value)

                    if (hasEpisodes) main {
                        delay(500)
                        temporaryNoFocus.isFocusable = false
                        // This might make some people sad as it changes the focus when leaving an episode :(
                        temporaryNoFocus.requestFocus()
                    }

                    if (hasNoFocus())
                        binding?.resultEpisodes?.requestFocus()
                }
            }
        }

        observeNullable(viewModel.page) { data ->
            if (data == null) return@observeNullable
            binding?.apply {
                when (data) {
                    is Resource.Success -> {
                        val d = data.value
                        resultVpn.setText(d.vpnText)
                        resultInfo.setText(d.metaText)
                        resultNoEpisodes.setText(d.noEpisodesFoundText)
                        resultTitle.setText(d.titleText)
                        resultMetaSite.setText(d.apiName)
                        resultMetaType.setText(d.typeText)
                        resultMetaYear.setText(d.yearText)
                        resultMetaDuration.setText(d.durationText)
                        resultMetaRating.setText(d.ratingText)
                        resultCastText.setText(d.actorsText)
                        resultNextAiring.setText(d.nextAiringEpisode)
                        resultNextAiringTime.setText(d.nextAiringDate)
                        resultPoster.setImage(d.posterImage)
                        resultDescription.setTextHtml(d.plotText)
                        resultDescription.setOnClickListener { view ->
                            view.context?.let { ctx ->
                                val builder: AlertDialog.Builder =
                                    AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                                builder.setMessage(d.plotText.asString(ctx).html())
                                    .setTitle(d.plotHeaderText.asString(ctx))
                                    .show()
                            }
                        }

                        backgroundPoster.setImage(d.posterBackgroundImage, radius = 10)

                        resultComingSoon.isVisible = d.comingSoon
                        resultDataHolder.isGone = d.comingSoon
                        UIHelper.populateChips(resultTag, d.tags)
                        resultCastItems.isGone = d.actors.isNullOrEmpty()
                        (resultCastItems.adapter as? ActorAdaptor)?.updateList(
                            d.actors ?: emptyList()
                        )
                    }

                    is Resource.Loading -> {

                    }

                    is Resource.Failure -> {
                        resultErrorText.text =
                            storedData.url.plus("\n") + data.errorString
                    }
                }

                resultFinishLoading.isVisible = data is Resource.Success

                resultLoading.isVisible = data is Resource.Loading

                resultLoadingError.isVisible = data is Resource.Failure
                //resultReloadConnectionOpenInBrowser.isVisible = data is Resource.Failure
            }
        }
    }
}