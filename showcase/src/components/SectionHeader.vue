<script setup lang="ts">
withDefaults(
  defineProps<{
    eyebrow: string
    title: string
    intro?: string
    align?: 'left' | 'center'
  }>(),
  {
    intro: '',
    align: 'left',
  },
)
</script>

<template>
  <header class="section-header" :class="`is-${align}`" data-reveal>
    <div class="eyebrow-row">
      <span class="eyebrow-index">{{ eyebrow.split(' / ')[0] }}</span>
      <span class="eyebrow-line" />
      <span class="eyebrow-copy">{{ eyebrow.split(' / ')[1] || eyebrow }}</span>
    </div>
    <h2>{{ title }}</h2>
    <p v-if="intro">{{ intro }}</p>
  </header>
</template>

<style scoped>
.section-header {
  max-width: 780px;
  margin-bottom: 48px;
}

.section-header.is-center {
  margin-inline: auto;
  text-align: center;
}

.eyebrow-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 22px;
  margin-bottom: 18px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.is-center .eyebrow-row {
  justify-content: center;
}

.eyebrow-index {
  color: var(--accent-cyan);
}

.eyebrow-line {
  width: 34px;
  height: 1px;
  background: var(--line-strong);
}

.eyebrow-copy {
  color: #8191a7;
}

h2 {
  max-width: 780px;
  margin: 0;
  color: var(--text-strong);
  font-size: clamp(2rem, 4vw, 3.8rem);
  font-weight: 620;
  letter-spacing: -0.035em;
  line-height: 1.08;
  text-wrap: balance;
}

p {
  max-width: 680px;
  margin: 20px 0 0;
  color: var(--text-secondary);
  font-size: clamp(1rem, 1.4vw, 1.1rem);
  line-height: 1.75;
}

.is-center p {
  margin-inline: auto;
}

@media (max-width: 760px) {
  .section-header {
    margin-bottom: 34px;
  }

  h2 {
    font-size: clamp(2rem, 10vw, 3rem);
    letter-spacing: -0.025em;
  }
}
</style>
