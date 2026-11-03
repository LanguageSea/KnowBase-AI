<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import ArchitectureSection from './components/ArchitectureSection.vue'
import CapabilitiesSection from './components/CapabilitiesSection.vue'
import EvidenceSection from './components/EvidenceSection.vue'
import HeroSection from './components/HeroSection.vue'
import OverviewSection from './components/OverviewSection.vue'
import PracticeSection from './components/PracticeSection.vue'
import SiteFooter from './components/SiteFooter.vue'
import SiteHeader from './components/SiteHeader.vue'
import WorkflowSection from './components/WorkflowSection.vue'

let revealObserver: IntersectionObserver | undefined

onMounted(() => {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  document.documentElement.classList.add('js-ready')

  if (reduceMotion) {
    document.querySelectorAll<HTMLElement>('[data-reveal]').forEach((element) => {
      element.classList.add('is-visible')
    })
    return
  }

  revealObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible')
          revealObserver?.unobserve(entry.target)
        }
      })
    },
    { threshold: 0.08, rootMargin: '0px 0px -8% 0px' },
  )

  document.querySelectorAll('[data-reveal]').forEach((element) => {
    revealObserver?.observe(element)
  })
})

onBeforeUnmount(() => {
  revealObserver?.disconnect()
})
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <SiteHeader />
  <main id="main-content">
    <HeroSection />
    <OverviewSection />
    <ArchitectureSection />
    <CapabilitiesSection />
    <WorkflowSection />
    <EvidenceSection />
    <PracticeSection />
  </main>
  <SiteFooter />
</template>
