<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import './styles/showcase.css'
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
  const root = document.querySelector<HTMLElement>('.showcase-app')
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  root?.classList.add('is-ready')

  if (reduceMotion) {
    root?.querySelectorAll<HTMLElement>('[data-reveal]').forEach((element) => {
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

  root?.querySelectorAll('[data-reveal]').forEach((element) => {
    revealObserver?.observe(element)
  })
})

onBeforeUnmount(() => {
  revealObserver?.disconnect()
})
</script>

<template>
  <div class="showcase-app">
    <a class="skip-link" href="#showcase-main">跳到主要内容</a>
    <SiteHeader />
    <main id="showcase-main">
      <HeroSection />
      <OverviewSection />
      <ArchitectureSection />
      <CapabilitiesSection />
      <WorkflowSection />
      <EvidenceSection />
      <PracticeSection />
    </main>
    <SiteFooter />
  </div>
</template>
