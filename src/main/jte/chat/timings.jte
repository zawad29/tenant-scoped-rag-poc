@import com.example.ragpoc.chat.ChatTimings
@import com.example.ragpoc.web.ViewFormat
@param ChatTimings timings
@if(timings == null)
  <div class="flex items-center justify-between py-1.5">
    <dt class="text-slate-500">Generation</dt>
    <dd class="text-xs text-slate-400">streaming&hellip;</dd>
  </div>
@else
  <div class="flex items-center justify-between py-1.5">
    <dt class="text-slate-500">First token</dt>
    <dd class="font-medium text-slate-700">${ViewFormat.msOrDash(timings.firstTokenMillis())}</dd>
  </div>
  <div class="flex items-center justify-between py-1.5">
    <dt class="text-slate-500">Generation</dt>
    <dd class="font-medium text-slate-700">${ViewFormat.msOrDash(timings.generationMillis())}</dd>
  </div>
  <div class="flex items-center justify-between py-1.5">
    <dt class="font-medium text-slate-700">Request total</dt>
    <dd class="font-semibold text-slate-900">${ViewFormat.ms(timings.totalMillis())}</dd>
  </div>
  @if(timings.refused())
    <div class="py-2 text-xs text-slate-500">
      No model call: the relevance gate refused before generation.
    </div>
  @endif
@endif
