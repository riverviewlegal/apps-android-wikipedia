package org.wikipedia.editactionfeed

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentStatePagerAdapter
import android.support.v4.view.ViewPager
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.fragment_add_title_descriptions.*
import org.wikipedia.Constants
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.analytics.RandomizerFunnel
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.mwapi.SiteMatrix
import org.wikipedia.descriptions.DescriptionEditActivity
import org.wikipedia.descriptions.DescriptionEditHelpActivity
import org.wikipedia.editactionfeed.AddTitleDescriptionsActivity.Companion.INIT_SOURCE
import org.wikipedia.editactionfeed.AddTitleDescriptionsActivity.Companion.MULTILINGUAL_DESC
import org.wikipedia.editactionfeed.AddTitleDescriptionsActivity.Companion.TITLE_DESC
import org.wikipedia.history.HistoryEntry
import org.wikipedia.page.PageActivity
import org.wikipedia.page.PageTitle
import org.wikipedia.settings.Prefs
import org.wikipedia.util.AnimationUtil
import org.wikipedia.util.log.L
import org.wikipedia.views.DialogTitleWithImage

class AddTitleDescriptionsFragment : Fragment() {
    private val viewPagerListener = ViewPagerListener()
    private var funnel: RandomizerFunnel? = null
    private val disposables = CompositeDisposable()
    private val app = WikipediaApp.getInstance()
    private var languageList: MutableList<String?> = mutableListOf()
    private var languageToList: MutableList<String?> = mutableListOf()
    private var languageCodesToList: MutableList<String?> = arrayListOf()
    var langFromCode: String = app.language().appLanguageCode
    var langToCode: String = if (app.language().appLanguageCodes.size == 1) "" else app.language().appLanguageCodes[1]
    var source: Int = TITLE_DESC
    var savedToLanguageSpinnerPosition: Int = -1
    var sourceDescription: CharSequence = ""
    var orientationChanged: Boolean = false

    private val topTitle: PageTitle?
        get() {
            val f = topChild
            return f?.title
        }

    private val topChild: AddTitleDescriptionsItemFragment?
        get() {
            val fm = fragmentManager
            for (f in fm!!.fragments) {
                if (f is AddTitleDescriptionsItemFragment && f.pagerPosition == addTitleDescriptionsItemPager.currentItem) {
                    return f
                }
            }
            return null
        }

    interface Callback {
        fun onLanguageLoaded(savedInstanceState: Bundle?)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        // TODO: add funnel?
        source= arguments?.getInt(INIT_SOURCE, TITLE_DESC)!!
        return inflater.inflate(R.layout.fragment_add_title_descriptions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        addTitleDescriptionsItemPager.offscreenPageLimit = 2
        addTitleDescriptionsItemPager.setPageTransformer(true, AnimationUtil.PagerTransformer())
        addTitleDescriptionsItemPager.addOnPageChangeListener(viewPagerListener)

        resetTitleDescriptionItemAdapter()
        requestLanguagesAndBuildSpinner(savedInstanceState, LanguageSpinnerCallback())

        skipButton.setOnClickListener { nextPage() }

        addDescriptionButton.setOnClickListener {
            if (topTitle != null) {
                startActivityForResult(DescriptionEditActivity.newIntent(requireContext(), topTitle!!, null, true, source == MULTILINGUAL_DESC, sourceDescription),
                        Constants.ACTIVITY_REQUEST_DESCRIPTION_EDIT)
            }
        }

        showOnboarding()
    }

    override fun onDestroyView() {
        disposables.clear()
        addTitleDescriptionsItemPager.removeOnPageChangeListener(viewPagerListener)
        if (funnel != null) {
            funnel!!.done()
            funnel = null
        }
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(FROM_LANG_CODE_POSITION, wikiFromLanguageSpinner.selectedItemPosition)
        outState.putInt(TO_LANG_CODE_POSITION, wikiToLanguageSpinner.selectedItemPosition)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Constants.ACTIVITY_REQUEST_DESCRIPTION_EDIT && resultCode == RESULT_OK) {
            nextPage()
        }
    }

    private fun nextPage() {
        viewPagerListener.setNextPageSelectedAutomatic()
        addTitleDescriptionsItemPager.setCurrentItem(addTitleDescriptionsItemPager.currentItem + 1, true)
    }

    fun onSelectPage(title: PageTitle) {
        startActivity(PageActivity.newIntentForNewTab(requireActivity(),
                HistoryEntry(title, HistoryEntry.SOURCE_RANDOM), title))
    }

    private fun showOnboarding() {
        if (Prefs.showEditActionAddTitleDescriptionsOnboarding()) {
            // TODO: update the title and description (T209539)
            AlertDialog.Builder(requireActivity())
                    .setCustomTitle(DialogTitleWithImage(requireActivity(), R.string.temp_add_title_descriptions_dialog_title, R.drawable.lead_default, false))
                    .setMessage(R.string.temp_add_title_descriptions_dialog_message)
                    .setPositiveButton(R.string.onboarding_got_it, null)
                    .setNegativeButton(R.string.editactionfeed_add_title_dialog_learn_more) {
                        _, _ -> startActivity(DescriptionEditHelpActivity.newIntent(requireContext()))
                    }
                    .show()
            Prefs.setShowEditActionAddTitleDescriptionsOnboarding(false)
        }
    }

    private fun requestLanguagesAndBuildSpinner(savedInstanceState: Bundle?, callback: Callback) {
        disposables.add(ServiceFactory.get(app.wikiSite).siteMatrix
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map { SiteMatrix.getSites(it) }
                .doFinally { callback.onLanguageLoaded(savedInstanceState) }
                .subscribe({ sites ->
                    for (code in app.language().appLanguageCodes) {
                        // TODO: confirm: do we have to show the "WIKIPEDIA" text after the language name?
                        languageList.add(getLanguageCanonicalName(sites, code))
                    }
                }, { L.e(it) }))
    }

    private fun getLanguageCanonicalName(sites: List<SiteMatrix.SiteInfo>, code: String): String {
        var canonicalName: String? = null
        for (info in sites) {
            if (code == info.code()) {
                canonicalName = info.localName()
                break
            }
        }
        if (TextUtils.isEmpty(canonicalName)) {
            canonicalName = app.language().getAppLanguageCanonicalName(code)
        }

        if (canonicalName == null) {
            canonicalName = code
        }

        return canonicalName
    }

    private fun resetTitleDescriptionItemAdapter() {
        addTitleDescriptionsItemPager.adapter = ViewPagerAdapter(requireActivity() as AppCompatActivity)
    }

    private inner class LanguageSpinnerCallback : Callback {
        override fun onLanguageLoaded(savedInstanceState: Bundle?) {
            if (languageList.size > 1) {
                val adapter = ArrayAdapter<String>(requireContext(), R.layout.item_language_spinner, languageList)
                adapter.setDropDownViewResource(R.layout.item_language_spinner)
                wikiFromLanguageSpinner.adapter = adapter
                wikiFromLanguageSpinner.onItemSelectedListener = OnSpinnerItemSelectedListener()
                if (savedInstanceState != null) {
                    orientationChanged = true
                    langFromCode = app.language().appLanguageCodes[savedInstanceState.getInt(FROM_LANG_CODE_POSITION)]
                    wikiFromLanguageSpinner.setSelection(savedInstanceState.getInt(FROM_LANG_CODE_POSITION))
                    wikiToLanguageSpinner.setSelection(savedInstanceState.getInt(TO_LANG_CODE_POSITION))
                    savedToLanguageSpinnerPosition = savedInstanceState.getInt(TO_LANG_CODE_POSITION)
                }
                wikiFromLanguageSpinner.visibility = VISIBLE
                wikiFromLanguageText.visibility = GONE
            } else {
                wikiFromLanguageText.text = languageList[0]
                wikiFromLanguageSpinner.visibility = GONE
                wikiFromLanguageText.visibility = VISIBLE
            }
        }
    }

    private fun updateMultilingualViews(fromLanguageSpinnerPosition: Int) {
        if (source == MULTILINGUAL_DESC) {
            arrows.visibility = VISIBLE
            languageToList.clear()
            languageCodesToList.clear()
            languageCodesToList.addAll(app.language().appLanguageCodes)
            languageCodesToList.removeAt(fromLanguageSpinnerPosition)
            addCanonicalNamesToLanguageToList()
            langToCode = languageCodesToList.get(if (orientationChanged) savedToLanguageSpinnerPosition else 0)!!

            if (languageToList.size > 1) {
                val toAdapter = ArrayAdapter<String>(requireContext(), R.layout.item_language_spinner, languageToList)
                wikiToLanguageSpinner.adapter = toAdapter
                wikiToLanguageSpinner.onItemSelectedListener = OnToSpinnerItemSelectedListener()
                wikiToLanguageText.visibility = GONE
                wikiToLanguageSpinner.visibility = VISIBLE
                if (orientationChanged) {
                    wikiToLanguageSpinner.setSelection(savedToLanguageSpinnerPosition)
                    orientationChanged = false
                }
            } else {
                wikiToLanguageText.text = languageToList[0]
                wikiToLanguageText.visibility = VISIBLE
                wikiToLanguageSpinner.visibility = GONE
            }
        } else {
            fromLabel.visibility = VISIBLE
        }
    }

    private fun addCanonicalNamesToLanguageToList() {
        for (language in languageCodesToList) {
            languageToList.add(app.language().getAppLanguageLocalizedName(language))
        }
    }

    private inner class OnSpinnerItemSelectedListener : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (langFromCode != app.language().appLanguageCodes[position]) {
                langFromCode = app.language().appLanguageCodes[position]
                L.d("Selected language code $langFromCode")
                resetTitleDescriptionItemAdapter()
            }
            updateMultilingualViews(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>) {
        }
    }

    private inner class OnToSpinnerItemSelectedListener : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            langToCode = languageCodesToList.get(position)!!
            resetTitleDescriptionItemAdapter()
        }
        override fun onNothingSelected(parent: AdapterView<*>) {
        }
    }

    private class ViewPagerAdapter internal constructor(activity: AppCompatActivity): FragmentStatePagerAdapter(activity.supportFragmentManager) {

        override fun getCount(): Int {
            return Integer.MAX_VALUE
        }

        override fun getItem(position: Int): Fragment {
            val f = AddTitleDescriptionsItemFragment.newInstance()
            f.pagerPosition = position
            return f
        }
    }

    private inner class ViewPagerListener : ViewPager.OnPageChangeListener {
        private var prevPosition: Int = 0
        private var nextPageSelectedAutomatic: Boolean = false

        internal fun setNextPageSelectedAutomatic() {
            nextPageSelectedAutomatic = true
        }

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

        override fun onPageSelected(position: Int) {
            if (!nextPageSelectedAutomatic && funnel != null) {
                if (position > prevPosition) {
                    funnel!!.swipedForward()
                } else if (position < prevPosition) {
                    funnel!!.swipedBack()
                }
            }
            nextPageSelectedAutomatic = false
            prevPosition = position
        }

        override fun onPageScrollStateChanged(state: Int) {}
    }

    companion object {
        const val FROM_LANG_CODE_POSITION = "fromLangCodePosition"
        const val TO_LANG_CODE_POSITION = "toLangCodePosition"
        fun newInstance(source: Int): AddTitleDescriptionsFragment {
            val addTitleDescriptionsFragment = AddTitleDescriptionsFragment()
            val args = Bundle()
            args.putInt(INIT_SOURCE, source)
            addTitleDescriptionsFragment.setArguments(args)
            return addTitleDescriptionsFragment
        }
    }
}